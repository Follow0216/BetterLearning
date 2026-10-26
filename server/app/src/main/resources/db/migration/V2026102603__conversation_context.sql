-- 明确父轮与作答来源分离；旧请求NULL保持无上下文行为，不回填猜测历史。
ALTER TABLE ma_request ADD COLUMN context_parent_request_id VARCHAR(36);
ALTER TABLE ma_request ADD COLUMN conversation_context_json TEXT;
ALTER TABLE ma_request ADD CONSTRAINT fk_ma_context_parent
    FOREIGN KEY (account_id,context_parent_request_id) REFERENCES ma_request(account_id,request_id);
ALTER TABLE ma_request ADD CONSTRAINT ck_ma_context_parent_not_self
    CHECK (context_parent_request_id IS NULL OR context_parent_request_id<>request_id);
ALTER TABLE ma_request ADD CONSTRAINT ck_ma_context_budget
    CHECK (conversation_context_json IS NULL OR
        (context_parent_request_id IS NOT NULL AND octet_length(conversation_context_json)<=65536));

-- 来源撤回和恢复后的journal重放都清除新引入的正文副本；不在触发器写request/run终态。
-- 后续execution/查询的账号锁短事务复用既有取消、fence、outbox和provider预算释放路径。
CREATE FUNCTION ma_scrub_conversation_context() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_TABLE_NAME='ma_source' THEN
        IF NEW.status='DELETED' THEN
            UPDATE ma_request q SET conversation_context_json=NULL
                WHERE q.account_id=NEW.account_id AND q.conversation_context_json IS NOT NULL
                AND EXISTS(SELECT 1 FROM jsonb_array_elements(q.conversation_context_json::jsonb->'turns') turn
                    WHERE turn->>'replySourceId'=NEW.source_id);
        END IF;
    ELSIF NEW.status='WITHDRAWN' OR NEW.purged_at IS NOT NULL THEN
        UPDATE ma_request q SET conversation_context_json=NULL
            WHERE q.account_id=(SELECT account_id FROM ma_source WHERE source_id=NEW.source_id)
            AND q.conversation_context_json IS NOT NULL
            AND EXISTS(SELECT 1 FROM jsonb_array_elements(q.conversation_context_json::jsonb->'turns') turn
                WHERE turn->>'replySourceId'=NEW.source_id AND (turn->>'replyVersion')::bigint=NEW.version);
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER ma_conversation_source_withdrawn AFTER UPDATE OF status ON ma_source
    FOR EACH ROW EXECUTE FUNCTION ma_scrub_conversation_context();
CREATE TRIGGER ma_conversation_version_withdrawn AFTER UPDATE OF status,purged_at ON ma_source_version
    FOR EACH ROW EXECUTE FUNCTION ma_scrub_conversation_context();
