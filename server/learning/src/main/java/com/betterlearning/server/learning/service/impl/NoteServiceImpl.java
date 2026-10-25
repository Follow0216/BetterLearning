package com.betterlearning.server.learning.service.impl;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
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
        lockAccount(accountId);
        NoteMapper.RequestRow prior = mapper.request(accountId, key);
        if (prior != null) {
            if (!prior.requestSha256().equals(content.requestSha256())) {
                throw failure(Failure.CONFLICT, "创建请求已用于其他内容");
            }
            return visibleNote(mapper.note(accountId, prior.noteId()));
        }
        String noteId = UUID.randomUUID().toString();
        SourceContent published = sources.publish(accountId, new PublishSource(SourceType.NOTE,
                noteId, content.title(), content.bodyMarkdown(), content.contentSha256(),
                null, null, null, "note:create:" + key));
        mapper.addTitle(published.source().sourceId(), published.version().version(), content.title());
        mapper.addRequest(accountId, key, noteId, published.source().sourceId(), content.requestSha256());
        return visibleNote(mapper.note(accountId, noteId));
    }

    @Override
    @Transactional(readOnly = true)
    public NoteModels.Note get(long accountId, String noteId) {
        return visibleNote(mapper.note(accountId, NoteContent.id(noteId)));
    }

    @Override
    @Transactional
    public NoteModels.Note update(long accountId, String noteId, String ifMatch, NoteModels.Write write) {
        String id = NoteContent.id(noteId);
        requireIfMatch(ifMatch);
        NoteContent content = content(write);
        lockAccount(accountId);
        NoteRow current = activeNote(mapper.note(accountId, id));
        checkEtag(ifMatch, id, current.version());
        SourceContent published = sources.publish(accountId, new PublishSource(SourceType.NOTE,
                id, content.title(), content.bodyMarkdown(), content.contentSha256(),
                null, null, current.version(), "note:update:" + id + ":" + (current.version() + 1)));
        mapper.addTitle(published.source().sourceId(), published.version().version(), content.title());
        return visibleNote(mapper.note(accountId, id));
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
            items.add(visibleNote(rows.get(index)));
        }
        String next = rows.size() > size ? encode(accountId, "NOTES",
                rows.get(size - 1).updatedAt(), rows.get(size - 1).noteId()) : null;
        return new NoteModels.Page(items, next);
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

    private static NoteModels.Note visibleNote(NoteRow row) {
        activeNote(row);
        return new NoteModels.Note(row.noteId(), row.sourceId(), row.version(), row.title(),
                row.bodyMarkdown(), row.indexStatus(), row.updatedAt());
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
