package com.betterlearning.server.learning.service.impl;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.betterlearning.server.enums.ErrorCode;
import com.betterlearning.server.exception.BusinessException;
import com.betterlearning.server.learning.mapper.SourceMapper;
import com.betterlearning.server.learning.model.dataobject.AnchorDO;
import com.betterlearning.server.learning.model.dataobject.SourceDO;
import com.betterlearning.server.learning.model.dataobject.SourceVersionDO;
import com.betterlearning.server.learning.model.dataobject.SourceCursorDO;
import com.betterlearning.server.learning.model.dataobject.SourceOperationDO;
import com.betterlearning.server.multiagent.MultiAgentModels.Anchor;
import com.betterlearning.server.multiagent.MultiAgentModels.PublishSource;
import com.betterlearning.server.multiagent.MultiAgentModels.Source;
import com.betterlearning.server.multiagent.MultiAgentModels.SourceContent;
import com.betterlearning.server.multiagent.MultiAgentModels.SourcePage;
import com.betterlearning.server.multiagent.MultiAgentModels.SourceType;
import com.betterlearning.server.multiagent.MultiAgentModels.SourceVersion;
import com.betterlearning.server.multiagent.SourceStore;
import com.betterlearning.server.learning.validation.AnswerManifestValidator;
import com.betterlearning.server.learning.validation.SourceAnchorValidator;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 账号锁串行化同账号版本分配，所有事实与 outbox 在一笔事务提交。 */
@Service
public class SourceStoreImpl implements SourceStore {
    private final SourceMapper mapper;
    private final SourceAnchorValidator anchorValidator;

    public SourceStoreImpl(SourceMapper mapper, SourceAnchorValidator anchorValidator) {
        this.mapper = mapper;
        this.anchorValidator = anchorValidator;
    }

    @Override
    @Transactional
    public SourceContent publish(long accountId, PublishSource command) {
        command = normalize(command);
        requireCommand(command);
        lockAccount(accountId);
        String fingerprint = fingerprint(command);
        SourceOperationDO prior = mapper.operation(accountId, command.operationId());
        if (prior != null) {
            if (!fingerprint.equals(prior.getPayloadFingerprint())) {
                throw new BusinessException(ErrorCode.ANSWER_SAVE_CONFLICT);
            }
            return get(accountId, prior.getSourceId(), prior.getVersion());
        }
        mapper.create(UUID.randomUUID().toString(), accountId, command.type().name(),
                command.originId(), command.title());
        SourceDO source = mapper.lockOrigin(accountId, command.type().name(), command.originId());
        if (source == null || !"ACTIVE".equals(source.getStatus())) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        long oldVersion = source.getCurrentVersion();
        if (command.type() == SourceType.TUTOR_SNAPSHOT && oldVersion > 0) {
            throw new BusinessException(ErrorCode.ANSWER_SAVE_CONFLICT);
        }
        if (command.expectedVersion() != null && command.expectedVersion() != oldVersion) {
            throw new BusinessException(ErrorCode.ANSWER_CHANGED);
        }
        long version = Math.addExact(oldVersion, 1);
        if (mapper.advance(accountId, source.getSourceId(), oldVersion, version, command.title()) != 1) {
            throw new BusinessException(ErrorCode.ANSWER_CHANGED);
        }
        mapper.insertVersion(source.getSourceId(), version, command.contentSha256(),
                command.answerState(), command.fileRef(), command.bodyMarkdown());
        mapper.outbox(UUID.randomUUID().toString(), accountId, source.getSourceId(), version,
                "SOURCE_VERSION_CREATED", command.contentSha256(), 1);
        mapper.recordOperation(accountId, command.operationId(), fingerprint, source.getSourceId(), version);
        return get(accountId, source.getSourceId(), version);
    }

    @Override
    @Transactional(readOnly = true)
    public SourceContent get(long accountId, String sourceId, long version) {
        SourceDO source = mapper.find(accountId, sourceId);
        SourceVersionDO content = mapper.version(accountId, sourceId, version);
        if (source == null || content == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        return new SourceContent(convert(source), convert(content), mapper.anchors(accountId, sourceId, version)
                .stream().map(SourceStoreImpl::convert).toList());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<SourceContent> findOrigin(long accountId, SourceType type, String originId) {
        if (type == null || originId == null) {
            return Optional.empty();
        }
        SourceDO source = mapper.findOrigin(accountId, type.name(), originId);
        return source == null || source.getCurrentVersion() == 0
                ? Optional.empty() : Optional.of(get(accountId, source.getSourceId(), source.getCurrentVersion()));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Source> list(long accountId, SourceType type, int limit) {
        if (type == null || limit < 1 || limit > 100) {
            throw new BusinessException(ErrorCode.INVALID_PARAM);
        }
        return mapper.list(accountId, type.name(), limit).stream().map(SourceStoreImpl::convert).toList();
    }

    @Override
    @Transactional
    public SourcePage page(long accountId, SourceType type, int limit, String cursor) {
        if (type == null || limit < 1 || limit > 100) {
            throw new BusinessException(ErrorCode.INVALID_PARAM);
        }
        SourceCursorDO before = null;
        if (cursor != null) {
            if (!AnswerManifestValidator.uuid(cursor)) {
                throw new BusinessException(ErrorCode.INVALID_PARAM);
            }
            before = mapper.cursor(cursor);
            if (before == null || before.getAccountId() != accountId || !type.name().equals(before.getType())) {
                throw new BusinessException(ErrorCode.INVALID_PARAM);
            }
        }
        List<SourceDO> rows = mapper.page(accountId, type.name(),
                before == null ? null : before.getBeforeTime(),
                before == null ? null : before.getBeforeId(), limit + 1);
        boolean more = rows.size() > limit;
        List<SourceDO> page = more ? rows.subList(0, limit) : rows;
        String nextCursor = null;
        if (more) {
            SourceDO last = page.get(page.size() - 1);
            nextCursor = UUID.randomUUID().toString();
            mapper.createCursor(nextCursor, accountId, type.name(), last.getCreatedAt(), last.getSourceId());
        }
        return new SourcePage(page.stream().map(SourceStoreImpl::convert).toList(), nextCursor);
    }

    @Override
    @Transactional
    public void addAnchors(long accountId, String sourceId, long version, List<Anchor> anchors) {
        addAnchors(accountId, sourceId, version, 1, anchors);
    }

    @Override
    @Transactional
    public void addAnchors(long accountId, String sourceId, long version,
            long expectedIndexRevision, List<Anchor> anchors) {
        if (anchors == null || anchors.isEmpty()) {
            return;
        }
        if (expectedIndexRevision < 1) {
            throw new BusinessException(ErrorCode.INVALID_PARAM);
        }
        lockAccount(accountId);
        SourceContent content = get(accountId, sourceId, version);
        Map<String, Anchor> existingByPosition = new HashMap<>();
        Set<String> usedIds = new HashSet<>();
        for (Anchor saved : content.anchors()) {
            existingByPosition.put(saved.kind() + ':' + saved.ordinal(), saved);
            usedIds.add(saved.anchorId());
        }
        for (Anchor anchor : anchors) {
            if (anchor == null || !sourceId.equals(anchor.sourceId()) || version != anchor.version()
                    || !AnswerManifestValidator.uuid(anchor.anchorId()) || anchor.kind() == null
                    || anchor.ordinal() < 0
                    || !digest(anchor.contentSha256())) {
                throw new BusinessException(ErrorCode.INVALID_PARAM);
            }
            String position = anchor.kind() + ':' + anchor.ordinal();
            Anchor existing = existingByPosition.get(position);
            if (existing != null) {
                if (!existing.equals(anchor)) {
                    throw new BusinessException(ErrorCode.ANSWER_SAVE_CONFLICT);
                }
                continue;
            }
            if (usedIds.contains(anchor.anchorId())) {
                throw new BusinessException(ErrorCode.ANSWER_SAVE_CONFLICT);
            }
            if (!"INDEX_PENDING".equals(content.version().status())
                    || content.source().currentVersion() != version
                    || content.version().indexRevision() != expectedIndexRevision) {
                throw new BusinessException(ErrorCode.ANSWER_CHANGED);
            }
            anchorValidator.validate(anchor, content);
            mapper.insertAnchor(anchor);
            existingByPosition.put(position, anchor);
            usedIds.add(anchor.anchorId());
        }
    }

    @Override
    @Transactional
    public boolean markIndex(long accountId, String sourceId, long version, String status) {
        return markIndex(accountId, sourceId, version, 1, status);
    }

    @Override
    @Transactional
    public boolean markIndex(long accountId, String sourceId, long version,
            long expectedIndexRevision, String status) {
        if (!("READY".equals(status) || "FAILED".equals(status))) {
            throw new BusinessException(ErrorCode.INVALID_PARAM);
        }
        lockAccount(accountId);
        return mapper.markIndex(accountId, sourceId, version, expectedIndexRevision, status) == 1;
    }

    @Override
    @Transactional
    public SourceVersion reindex(long accountId, String sourceId, long version, long expectedIndexRevision) {
        lockAccount(accountId);
        SourceContent current = get(accountId, sourceId, version);
        if (current.source().currentVersion() != version || expectedIndexRevision < 1
                || mapper.reindex(accountId, sourceId, version, expectedIndexRevision) != 1) {
            throw new BusinessException(ErrorCode.ANSWER_CHANGED);
        }
        long nextRevision = Math.addExact(expectedIndexRevision, 1);
        mapper.outbox(UUID.randomUUID().toString(), accountId, sourceId, version,
                "SOURCE_REINDEX", current.version().contentSha256(), nextRevision);
        return get(accountId, sourceId, version).version();
    }

    @Override
    @Transactional
    public void withdraw(long accountId, String sourceId) {
        lockAccount(accountId);
        SourceDO source = mapper.find(accountId, sourceId);
        if (source == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        SourceVersionDO current = mapper.version(accountId, sourceId, source.getCurrentVersion());
        if (mapper.withdrawRename(accountId, sourceId, source.getOriginId() + "#deleted:" + sourceId) != 1) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
        mapper.outbox(UUID.randomUUID().toString(), accountId, sourceId, source.getCurrentVersion(),
                "SOURCE_WITHDRAWN", current.getContentSha256(), current.getIndexRevision());
    }

    @Override
    @Transactional(readOnly = true)
    public boolean fileReferenced(String fileRef) {
        return fileRef != null && mapper.fileReferences(fileRef) > 0;
    }

    private void lockAccount(long accountId) {
        if (mapper.lockAccount(accountId) == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND);
        }
    }

    private static void requireCommand(PublishSource command) {
        if (command == null || command.type() == null || command.originId() == null
                || command.originId().isBlank() || command.originId().length() > 80
                || command.title() == null || command.operationId() == null
                || command.operationId().isBlank() || command.operationId().length() > 160
                || !digest(command.contentSha256())
                || ((command.type() == SourceType.QUESTION || command.type() == SourceType.NOTE
                || command.type() == SourceType.TUTOR_REPLY)
                && (command.bodyMarkdown() == null || !command.contentSha256().equals(
                AnswerManifestValidator.sha256(command.bodyMarkdown().getBytes(StandardCharsets.UTF_8)))))
                || (command.type() == SourceType.SAVED_ANSWER && (command.fileRef() == null
                || !("DRAFT".equals(command.answerState()) || "COMPLETED".equals(command.answerState()))))) {
            throw new BusinessException(ErrorCode.INVALID_PARAM);
        }
    }

    private static boolean digest(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static PublishSource normalize(PublishSource value) {
        if (value == null) {
            return null;
        }
        String body = value.bodyMarkdown() == null ? null : value.bodyMarkdown().replace("\r\n", "\n");
        return new PublishSource(value.type(), value.originId(), value.title(), body,
                value.contentSha256(), value.fileRef(), value.answerState(),
                value.expectedVersion(), value.operationId());
    }

    private static String fingerprint(PublishSource value) {
        StringBuilder payload = new StringBuilder();
        part(payload, value.type().name());
        part(payload, value.originId());
        part(payload, value.title());
        part(payload, value.bodyMarkdown());
        part(payload, value.contentSha256());
        part(payload, value.fileRef());
        part(payload, value.answerState());
        part(payload, value.expectedVersion() == null ? null : value.expectedVersion().toString());
        return AnswerManifestValidator.sha256(payload.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void part(StringBuilder payload, String value) {
        if (value == null) {
            payload.append("-1:");
        } else {
            payload.append(value.length()).append(':').append(value);
        }
    }

    private static Source convert(SourceDO value) {
        return new Source(value.getSourceId(), value.getAccountId(), SourceType.valueOf(value.getType()),
                value.getOriginId(), value.getTitle(), value.getStatus(), value.getCurrentVersion(),
                value.getCreatedAt());
    }

    private static SourceVersion convert(SourceVersionDO value) {
        return new SourceVersion(value.getSourceId(), value.getVersion(), value.getContentSha256(),
                value.getStatus(), value.getAnswerState(), value.getFileRef(), value.getBodyMarkdown(),
                value.getCreatedAt(), value.getIndexRevision());
    }

    private static Anchor convert(AnchorDO value) {
        return new Anchor(value.getAnchorId(), value.getSourceId(), value.getVersion(), value.getKind(),
                value.getOrdinal(), value.getContentSha256(), value.getLocatorJson(), value.getText());
    }
}
