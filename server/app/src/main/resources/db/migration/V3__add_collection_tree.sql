ALTER TABLE question_collection ADD COLUMN parent_collection_id BIGINT;
ALTER TABLE question_collection ADD CONSTRAINT fk_question_collection_owned_parent
    FOREIGN KEY (account_id, parent_collection_id)
    REFERENCES question_collection(account_id, collection_id);
ALTER TABLE question_collection ADD CONSTRAINT ck_question_collection_not_self_parent
    CHECK (parent_collection_id IS NULL OR parent_collection_id <> collection_id);
CREATE UNIQUE INDEX uk_question_collection_sibling_name
    ON question_collection(account_id, COALESCE(parent_collection_id, 0), name);
