package com.betterlearning.server.learning;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.betterlearning.server.learning.dao.LearningDao;
import com.betterlearning.server.learning.mapper.MultiAgentMapper;
import com.betterlearning.server.learning.mapper.TabletPresenceMapper;
import com.betterlearning.server.learning.mapper.TabletPresenceMapper.Lease;
import com.betterlearning.server.learning.model.dataobject.QuestionDO;
import com.betterlearning.server.learning.service.impl.PostgresTabletPresence;
import com.betterlearning.server.multiagent.TabletPresence.Heartbeat;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class PostgresTabletPresenceTests {
    private static final String SESSION = "f1a6b2d9-3649-4df1-baf1-016e657e0330";
    private final TabletPresenceMapper db = mock(TabletPresenceMapper.class);
    private final MultiAgentMapper accounts = mock(MultiAgentMapper.class);
    private final LearningDao questions = mock(LearningDao.class);
    private final PostgresTabletPresence presence = new PostgresTabletPresence(db, accounts, questions);

    @Test
    void releaseTombstoneAndDuplicateHeartbeatDoNotRenewOrResurrectLease() {
        when(accounts.lock(7)).thenReturn(7L);
        Instant released = Instant.now().minusSeconds(1);
        when(db.lease(7, SESSION)).thenReturn(new Lease(7, SESSION, 12, null, false, released));
        var old = presence.heartbeat(7, new Heartbeat(SESSION, 11, "91", true));
        var repeated = presence.heartbeat(7, new Heartbeat(SESSION, 12, null, false));
        assertThat(old.active()).isFalse();
        assertThat(repeated.leaseExpiresAt()).isEqualTo(released);
        assertThat(repeated.sequence()).isEqualTo(12);
        verify(db, never()).upsert(any());
    }

    @Test
    void availabilitySeparatesAccountScopeOtherQuestionOfflineAndNoDevice() {
        when(accounts.lock(7)).thenReturn(7L);
        when(questions.findById(7, 91)).thenReturn(Optional.of(new QuestionDO()));
        when(db.leases(7)).thenReturn(List.of());
        assertThat(presence.availability(7, "91").reason()).isEqualTo("NO_DEVICE");
        when(db.leases(7)).thenReturn(List.of(new Lease(7, SESSION, 1, 92L, true, Instant.now().plusSeconds(20))));
        assertThat(presence.availability(7, "91").reason()).isEqualTo("OTHER_QUESTION");
        when(db.leases(7)).thenReturn(List.of(new Lease(7, SESSION, 1, 91L, true, Instant.now().minusSeconds(1))));
        assertThat(presence.availability(7, "91").reason()).isEqualTo("OFFLINE");
        when(db.leases(7)).thenReturn(List.of(new Lease(7, SESSION, 1, 91L, true, Instant.now().plusSeconds(20))));
        assertThat(presence.availability(7, "91").available()).isTrue();
        verify(db, org.mockito.Mockito.times(4)).leases(7);
    }

    @Test
    void presenceDatabaseFailureIsNeverClassifiedAsNoDevice() {
        when(accounts.lock(7)).thenReturn(7L);
        when(questions.findById(7, 91)).thenReturn(Optional.of(new QuestionDO()));
        when(db.leases(7)).thenThrow(new DataAccessResourceFailureException("unavailable"));
        assertThatThrownBy(() -> presence.availability(7, "91"))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }
}
