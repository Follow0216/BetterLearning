package com.betterlearning.server.learning.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.betterlearning.server.enums.ErrorCode;
import com.betterlearning.server.exception.BusinessException;
import com.betterlearning.server.learning.mapper.NoteMapper;
import com.betterlearning.server.learning.mapper.NoteMapper.NoteRow;
import com.betterlearning.server.learning.mapper.NoteMapper.SourceRow;
import com.betterlearning.server.learning.model.note.NoteModels;
import com.betterlearning.server.learning.service.NoteService;
import com.betterlearning.server.learning.validation.NoteContent;
import com.betterlearning.server.multiagent.MultiAgentModels.PublishSource;
import com.betterlearning.server.multiagent.MultiAgentModels.SourceContent;
import com.betterlearning.server.multiagent.MultiAgentModels.SourceType;
import com.betterlearning.server.multiagent.SourceStore;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 笔记事实与去重同事务发布；账号行锁串行化 CAS 与重复创建。 */
@Service
public class NoteServiceImpl implements NoteService {
    private final NoteMapper mapper;
    private final SourceStore sources;
    private final byte[] cursorKey;

    public NoteServiceImpl(NoteMapper mapper, SourceStore sources,
            @Value("${app.jwt.secret-base64}") String cursorSecretBase64) {
        this.mapper = mapper;
        this.sources = sources;
        byte[] rootKey = Base64.getDecoder().decode(cursorSecretBase64);
        if (rootKey.length < 32) {
            throw new IllegalArgumentException("cursor signing key too short");
        }
        this.cursorKey = mac(rootKey, "betterlearning-notes-cursor-v1".getBytes(StandardCharsets.UTF_8));
    }

    @Override
    @Transactional
    public NoteModels.Note create(long accountId, String idempotencyKey, NoteModels.Write write) {
        String key = NoteContent.id(idempotencyKey);
        NoteContent content = content(write);
        NoteModels.ContextWrite context = normalizeContext(write.context());
        String fingerprint = context == null ? content.requestSha256() : contextFingerprint(content, context);
        lockAccount(accountId);
        NoteMapper.RequestRow prior = mapper.request(accountId, key);
        if (prior != null) {
            if (!prior.requestSha256().equals(fingerprint)) {
                throw failure(Failure.CONFLICT, "创建请求已用于其他内容");
            }
            return visibleNote(accountId, mapper.note(accountId, prior.noteId()));
        }
        NoteMapper.ExcerptRow excerptSource = validateContext(accountId, context);
        String noteId = UUID.randomUUID().toString();
        SourceContent published = sources.publish(accountId, new PublishSource(SourceType.NOTE,
                noteId, content.title(), content.bodyMarkdown(), content.contentSha256(),
                null, null, null, "note:create:" + key));
        mapper.addTitle(published.source().sourceId(), published.version().version(), content.title());
        mapper.addRequest(accountId, key, noteId, published.source().sourceId(), fingerprint);
        mapper.addContext(accountId, noteId, context == null ? null : context.notebookId(),
                context == null ? null : context.questionId(), context == null ? null : context.excerpt(),
                excerptSource, excerptSource == null ? null : hash(excerptSource.bodyMarkdown()));
        return visibleNote(accountId, mapper.note(accountId, noteId));
    }

    @Override
    @Transactional(readOnly = true)
    public NoteModels.Note get(long accountId, String noteId) {
        return visibleNote(accountId, mapper.note(accountId, NoteContent.id(noteId)));
    }

    @Override
    @Transactional
    public NoteModels.Note update(long accountId, String noteId, String ifMatch, NoteModels.Write write) {
        String id = NoteContent.id(noteId);
        requireIfMatch(ifMatch);
        NoteContent content = content(write);
        if (write.context() != null) {
            throw failure(Failure.INVALID, "正文更新不能修改归类或出处，请使用独立归类接口");
        }
        lockAccount(accountId);
        NoteRow current = activeNote(mapper.note(accountId, id));
        checkEtag(ifMatch, id, current.version());
        SourceContent published = sources.publish(accountId, new PublishSource(SourceType.NOTE,
                id, content.title(), content.bodyMarkdown(), content.contentSha256(),
                null, null, current.version(), "note:update:" + id + ":" + (current.version() + 1)));
        mapper.addTitle(published.source().sourceId(), published.version().version(), content.title());
        return visibleNote(accountId, mapper.note(accountId, id));
    }

    @Override
    @Transactional
    public void delete(long accountId, String noteId, String ifMatch) {
        String id = NoteContent.id(noteId);
        requireIfMatch(ifMatch);
        lockAccount(accountId);
        NoteRow current = mapper.note(accountId, id);
        if (current == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        checkEtag(ifMatch, id, current.version());
        if ("ACTIVE".equals(current.sourceStatus())) {
            sources.withdraw(accountId, current.sourceId());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public NoteModels.Page list(long accountId, String cursor, Integer limit) {
        int size = pageSize(limit);
        Position before = decode(cursor, accountId, "NOTES");
        List<NoteRow> rows = mapper.notes(accountId, before == null ? null : before.time(),
                before == null ? null : before.id(), size + 1);
        List<NoteModels.Note> items = new ArrayList<>();
        for (int index = 0; index < Math.min(size, rows.size()); index++) {
            items.add(visibleNote(accountId, rows.get(index)));
        }
        String next = rows.size() > size ? encode(accountId, "NOTES",
                rows.get(size - 1).updatedAt(), rows.get(size - 1).noteId()) : null;
        return new NoteModels.Page(items, next);
    }

    @Override
    @Transactional(readOnly = true)
    public NoteModels.Page search(long accountId, String query, String notebookId, String questionId,
            String cursor, Integer limit) {
        String q = query == null ? "" : query.strip();
        if (q.length() > 200 || q.indexOf(0) >= 0) {
            throw failure(Failure.INVALID, "搜索词不能超过 200 字");
        }
        String book = notebookId == null || "unfiled".equals(notebookId) ? null : NoteContent.id(notebookId);
        String question = questionId == null ? null : questionId(questionId);
        if (book != null) {
            notebook(accountId, book);
        }
        if (question != null && !mapper.ownedQuestion(accountId, question)) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        boolean unfiled = "unfiled".equals(notebookId);
        String kind = "SEARCH:" + hash(parts(q, book, Boolean.toString(unfiled), question));
        Position before = decode(cursor, accountId, kind);
        int size = pageSize(limit);
        String pattern = q.isEmpty() ? null : "%" + q.replace("!", "!!").replace("%", "!%")
                .replace("_", "!_") + "%";
        List<NoteRow> rows = mapper.search(accountId, pattern, book, unfiled, question,
                before == null ? null : before.time(), before == null ? null : before.id(), size + 1);
        List<NoteModels.Note> items = new ArrayList<>();
        for (int index = 0; index < Math.min(size, rows.size()); index++) {
            items.add(visibleNote(accountId, rows.get(index)));
        }
        String next = rows.size() > size ? encode(accountId, kind,
                rows.get(size - 1).updatedAt(), rows.get(size - 1).noteId()) : null;
        return new NoteModels.Page(items, next);
    }

    @Override
    @Transactional
    public NoteModels.Note file(long accountId, String noteId, String ifMatch, NoteModels.Filing filing) {
        String id = NoteContent.id(noteId);
        if (filing == null) {
            throw failure(Failure.INVALID, "归类参数不可为空");
        }
        String book = filing.notebookId() == null ? null : NoteContent.id(filing.notebookId());
        lockAccount(accountId);
        activeNote(mapper.note(accountId, id));
        NoteModels.Context current = context(accountId, id);
        checkRevision(ifMatch, "context", id, current.revision());
        if (book != null) {
            notebook(accountId, book);
        }
        if (!java.util.Objects.equals(book, current.notebookId())) {
            mapper.fileNote(accountId, id, book);
        }
        return visibleNote(accountId, mapper.note(accountId, id));
    }

    @Override
    @Transactional(readOnly = true)
    public NoteModels.Notebooks notebooks(long accountId) {
        return new NoteModels.Notebooks(mapper.notebooks(accountId));
    }

    @Override
    @Transactional
    public NoteModels.Notebook createNotebook(long accountId, String key, NoteModels.NotebookWrite write) {
        String id = NoteContent.id(key);
        String name = notebookName(write);
        lockAccount(accountId);
        NoteModels.Notebook prior = mapper.notebook(accountId, id);
        if (prior != null) {
            if (!prior.name().equals(name)) {
                throw failure(Failure.CONFLICT, "创建请求已用于其他笔记本名称");
            }
            return prior;
        }
        mapper.addNotebook(accountId, id, name);
        return notebook(accountId, id);
    }

    @Override
    @Transactional
    public NoteModels.Notebook renameNotebook(long accountId, String notebookId, String ifMatch,
            NoteModels.NotebookWrite write) {
        String id = NoteContent.id(notebookId);
        String name = notebookName(write);
        lockAccount(accountId);
        NoteModels.Notebook current = notebook(accountId, id);
        checkRevision(ifMatch, "notebook", id, current.revision());
        mapper.renameNotebook(accountId, id, name);
        return notebook(accountId, id);
    }

    @Override
    @Transactional
    public void deleteNotebook(long accountId, String notebookId, String ifMatch, boolean confirmed) {
        if (!confirmed) {
            throw failure(Failure.INVALID, "删除笔记本需要确认，笔记将保留在未归类");
        }
        String id = NoteContent.id(notebookId);
        lockAccount(accountId);
        NoteModels.Notebook current = notebook(accountId, id);
        checkRevision(ifMatch, "notebook", id, current.revision());
        mapper.unfileNotebook(accountId, id);
        mapper.removeNotebook(accountId, id);
    }

    private NoteModels.Notebook notebook(long accountId, String notebookId) {
        NoteModels.Notebook book = mapper.notebook(accountId, notebookId);
        if (book == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return book;
    }

    private NoteModels.Context context(long accountId, String noteId) {
        NoteMapper.ContextRow row = mapper.context(accountId, noteId);
        if (row == null) {
            return new NoteModels.Context(null, null, 1, null);
        }
        NoteModels.Excerpt excerpt = row.resultId() == null && row.turnId() == null ? null
                : new NoteModels.Excerpt(row.resultId(), row.turnId(), row.requestId(), row.sourceId(),
                row.sourceVersion(), row.versionHash(), row.available() ? row.excerptText() : null, row.available());
        return new NoteModels.Context(row.notebookId(), row.questionId(), row.revision(), excerpt);
    }

    private static NoteModels.ContextWrite normalizeContext(NoteModels.ContextWrite context) {
        if (context == null) {
            return null;
        }
        String book = context.notebookId() == null ? null : NoteContent.id(context.notebookId());
        String question = context.questionId() == null ? null : questionId(context.questionId());
        NoteModels.ExcerptWrite excerpt = context.excerpt();
        if (excerpt != null) {
            if (question == null || (excerpt.resultId() == null) == (excerpt.turnId() == null)
                    || excerpt.text() == null || excerpt.text().isBlank()
                    || excerpt.text().getBytes(StandardCharsets.UTF_8).length > 16384) {
                throw failure(Failure.INVALID, "摘录必须来自本题已完成辅导的明确段落，最多 16 KiB");
            }
            excerpt = new NoteModels.ExcerptWrite(excerpt.resultId() == null ? null : NoteContent.id(excerpt.resultId()),
                    excerpt.turnId() == null ? null : NoteContent.id(excerpt.turnId()),
                    excerpt.text().replace("\r\n", "\n"));
        }
        return new NoteModels.ContextWrite(book, question, excerpt);
    }

    private NoteMapper.ExcerptRow validateContext(long accountId, NoteModels.ContextWrite context) {
        if (context == null) {
            return null;
        }
        if (context.notebookId() != null) {
            notebook(accountId, context.notebookId());
        }
        if (context.questionId() != null && !mapper.ownedQuestion(accountId, context.questionId())) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        NoteModels.ExcerptWrite excerpt = context.excerpt();
        if (excerpt == null) {
            return null;
        }
        NoteMapper.ExcerptRow source = excerpt.resultId() == null
                ? mapper.turnExcerpt(accountId, context.questionId(), excerpt.turnId())
                : mapper.resultExcerpt(accountId, context.questionId(), excerpt.resultId());
        if (source == null || source.bodyMarkdown() == null
                || !java.util.Arrays.asList(source.bodyMarkdown().replace("\r\n", "\n").split("\\n{2,}"))
                .contains(excerpt.text())) {
            throw failure(Failure.INVALID, "辅导出处不可访问或摘录与原文不符");
        }
        return source;
    }

    private static String questionId(String value) {
        try {
            long id = Long.parseLong(value);
            if (id < 1 || !Long.toString(id).equals(value)) {
                throw new IllegalArgumentException();
            }
            return value;
        } catch (RuntimeException exception) {
            throw failure(Failure.INVALID, "题目编号不合法");
        }
    }

    private static String notebookName(NoteModels.NotebookWrite write) {
        if (write == null || write.name() == null || write.name().strip().isEmpty()
                || write.name().strip().length() > 64 || write.name().indexOf(0) >= 0) {
            throw failure(Failure.INVALID, "笔记本名称应为 1 到 64 字");
        }
        return write.name().strip();
    }

    private static void checkRevision(String tag, String kind, String id, long revision) {
        requireIfMatch(tag);
        if (!("\"" + kind + ":" + id + ":" + revision + "\"").equals(tag)) {
            throw failure(Failure.PRECONDITION, "归类或笔记本版本已变化，请刷新后重试");
        }
    }

    private static String contextFingerprint(NoteContent content, NoteModels.ContextWrite context) {
        NoteModels.ExcerptWrite excerpt = context.excerpt();
        return hash(parts(content.requestSha256(), context.notebookId(), context.questionId(),
                excerpt == null ? null : excerpt.resultId(), excerpt == null ? null : excerpt.turnId(),
                excerpt == null ? null : excerpt.text()));
    }

    private static String parts(String... values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            result.append(value == null ? "-1:" : value.length() + ":" + value);
        }
        return result.toString();
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public SourceView source(long accountId, String sourceId) {
        SourceRow row = mapper.source(accountId, NoteContent.id(sourceId));
        if (row == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return view(row);
    }

    @Override
    @Transactional(readOnly = true)
    public SourcePage sources(long accountId, String type, String cursor, Integer limit) {
        String filter = sourceType(type);
        int size = pageSize(limit);
        Position before = decode(cursor, accountId, "SOURCES:" + (filter == null ? "ALL" : filter));
        List<SourceRow> rows = mapper.sources(accountId, filter, before == null ? null : before.time(),
                before == null ? null : before.id(), size + 1);
        List<SourceView> items = new ArrayList<>();
        for (int index = 0; index < Math.min(size, rows.size()); index++) {
            items.add(view(rows.get(index)));
        }
        String next = rows.size() > size ? encode(accountId,
                "SOURCES:" + (filter == null ? "ALL" : filter),
                rows.get(size - 1).createdAt(), rows.get(size - 1).sourceId()) : null;
        return new SourcePage(items, next);
    }

    private static NoteContent content(NoteModels.Write write) {
        if (write == null) {
            throw failure(Failure.INVALID, "笔记正文不可为空");
        }
        return NoteContent.validate(write.title(), write.bodyMarkdown());
    }

    private void lockAccount(long accountId) {
        if (mapper.lockAccount(accountId) == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
    }

    private static NoteRow activeNote(NoteRow row) {
        if (row == null || !"ACTIVE".equals(row.sourceStatus())) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return row;
    }

    private NoteModels.Note visibleNote(long accountId, NoteRow row) {
        activeNote(row);
        return new NoteModels.Note(row.noteId(), row.sourceId(), row.version(), row.title(),
                row.bodyMarkdown(), row.indexStatus(), row.updatedAt(), context(accountId, row.noteId()));
    }

    private static void requireIfMatch(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            throw failure(Failure.INVALID, "缺少 If-Match");
        }
    }

    private static void checkEtag(String ifMatch, String noteId, long version) {
        if (!NoteContent.matches(ifMatch, noteId, version)) {
            throw failure(Failure.PRECONDITION, "笔记版本已变化，请刷新");
        }
    }

    private static int pageSize(Integer limit) {
        int size = limit == null ? 20 : limit;
        if (size < 1 || size > 100) {
            throw failure(Failure.INVALID, "limit 必须为 1 到 100");
        }
        return size;
    }

    private static String sourceType(String type) {
        if (type == null) {
            return null;
        }
        try {
            return SourceType.valueOf(type).name();
        } catch (IllegalArgumentException exception) {
            throw failure(Failure.INVALID, "来源类型不合法");
        }
    }

    private static SourceView view(SourceRow row) {
        return new SourceView(row.sourceId(), row.type(), row.originId(), row.status(),
                row.createdAt(), row.currentVersion(), row.indexStatus(), row.answerState());
    }

    private record Position(Instant time, String id) {
    }

    private String encode(long accountId, String kind, Instant time, String id) {
        String payload = "v1\n" + accountId + "\n" + kind + "\n" + time + "\n" + id;
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        Base64.Encoder encoder = Base64.getUrlEncoder().withoutPadding();
        return encoder.encodeToString(bytes) + "." + encoder.encodeToString(mac(bytes));
    }

    private Position decode(String cursor, long accountId, String kind) {
        if (cursor == null) {
            return null;
        }
        try {
            if (cursor.length() > 1024) {
                throw new IllegalArgumentException();
            }
            String[] parts = cursor.split("\\.", -1);
            if (parts.length != 2) {
                throw new IllegalArgumentException();
            }
            Base64.Decoder decoder = Base64.getUrlDecoder();
            byte[] payload = decoder.decode(parts[0]);
            if (!java.security.MessageDigest.isEqual(mac(payload), decoder.decode(parts[1]))) {
                throw new IllegalArgumentException();
            }
            String[] values = new String(payload, StandardCharsets.UTF_8).split("\n", -1);
            if (values.length != 5 || !"v1".equals(values[0])
                    || !Long.toString(accountId).equals(values[1]) || !kind.equals(values[2])) {
                throw new IllegalArgumentException();
            }
            return new Position(Instant.parse(values[3]), NoteContent.id(values[4]));
        } catch (RuntimeException exception) {
            throw failure(Failure.INVALID, "分页游标不合法");
        }
    }

    private byte[] mac(byte[] payload) {
        return mac(cursorKey, payload);
    }

    private static byte[] mac(byte[] key, byte[] payload) {
        try {
            Mac algorithm = Mac.getInstance("HmacSHA256");
            algorithm.init(new SecretKeySpec(key, "HmacSHA256"));
            return algorithm.doFinal(payload);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("cursor signature unavailable", exception);
        }
    }

    private static NoteFailure failure(Failure type, String message) {
        return new NoteFailure(type, message);
    }
}
