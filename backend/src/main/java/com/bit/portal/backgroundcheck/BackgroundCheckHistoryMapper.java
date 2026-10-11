package com.bit.portal.backgroundcheck;

import java.time.OffsetDateTime;
import java.util.*;
import org.apache.ibatis.annotations.*;

public interface BackgroundCheckHistoryMapper {
    String SELECT="SELECT b.request_id,b.employee_id,b.requested_by,a.username AS requested_by_username,b.requested_at,b.dispatch_deadline,"
        +"b.check_id,b.local_state,b.status,b.observed_at,b.completed_at,b.last_http_code,b.last_outcome,b.poll_deadline,b.next_poll_at,"
        +"b.poll_count,b.poll_stopped,b.stop_reason,b.refreshing_until FROM background_checks b JOIN user_accounts a ON a.id=b.requested_by ";
    @Select(SELECT+"WHERE b.request_id=#{requestId}") BackgroundCheckHistory find(String requestId);
    @Select(SELECT+"WHERE b.check_id=#{checkId}") BackgroundCheckHistory findByCheck(String checkId);
    @Select(SELECT+"WHERE b.employee_id=#{id} ORDER BY b.requested_at DESC, b.request_id LIMIT #{limit} OFFSET #{offset}")
    List<BackgroundCheckHistory> list(@Param("id") String id,@Param("limit") int limit,@Param("offset") int offset);
    @Select("SELECT COUNT(*) FROM background_checks WHERE employee_id=#{id}") int count(String id);
    @Select("SELECT COUNT(*) FROM background_checks WHERE employee_id=#{id} AND (local_state IN ('DISPATCHING','UNKNOWN') OR status='pending')")
    int blockers(String id);
    @Select("SELECT COUNT(*) FROM background_checks WHERE employee_id=#{id} AND status IN ('clear','flagged')") int completed(String id);
    @Insert("""
        INSERT INTO background_checks(request_id,employee_id,requested_by,dispatch_deadline,local_state,poll_deadline)
        VALUES(#{request},#{employee},#{actor},#{dispatch},'DISPATCHING',#{deadline})
        """)
    void reserve(@Param("request") String request,@Param("employee") String employee,@Param("actor") long actor,
        @Param("dispatch") OffsetDateTime dispatch,@Param("deadline") OffsetDateTime deadline);
    @Update("""
        UPDATE background_checks SET local_state='UNKNOWN',last_outcome='INTERRUPTED',poll_stopped=TRUE,stop_reason='RESULT_UNKNOWN'
        WHERE employee_id=#{id} AND local_state='DISPATCHING' AND dispatch_deadline < #{now}
        """) void expireDispatch(@Param("id") String id,@Param("now") OffsetDateTime now);
    @Update("""
        UPDATE background_checks SET poll_stopped=TRUE,stop_reason='POLL_LIMIT'
        WHERE employee_id=#{id} AND local_state='KNOWN' AND status='pending' AND poll_stopped=FALSE AND poll_deadline<=#{now}
        """) void expirePolling(@Param("id") String id,@Param("now") OffsetDateTime now);
    @Update("""
        UPDATE background_checks SET local_state=#{state},check_id=#{check},status=#{status},observed_at=#{now},
          completed_at=#{completed},last_http_code=#{http},last_outcome=#{outcome},next_poll_at=#{next},
          poll_stopped=#{stopped},stop_reason=#{reason}
        WHERE request_id=#{id} AND local_state IN ('DISPATCHING','UNKNOWN')
        """)
    int finish(@Param("id") String id,@Param("state") String state,@Param("check") String check,@Param("status") String status,
        @Param("now") OffsetDateTime now,@Param("completed") OffsetDateTime completed,@Param("http") Integer http,
        @Param("outcome") String outcome,@Param("next") OffsetDateTime next,@Param("stopped") boolean stopped,@Param("reason") String reason);
    @Update("""
        UPDATE background_checks SET refreshing_until=#{lease},poll_count=poll_count+#{increment},next_poll_at=#{next}
        WHERE request_id=#{id} AND local_state='KNOWN' AND (refreshing_until IS NULL OR refreshing_until < #{now})
          AND (next_poll_at IS NULL OR next_poll_at <= #{now})
          AND (#{increment}=0 OR (status='pending' AND poll_stopped=FALSE AND poll_deadline>#{now} AND poll_count<#{max}))
        """)
    int claim(@Param("id") String id,@Param("now") OffsetDateTime now,@Param("lease") OffsetDateTime lease,
        @Param("next") OffsetDateTime next,@Param("increment") int increment,@Param("max") int max);
    @Update("""
        UPDATE background_checks SET status=#{status},observed_at=#{now},completed_at=COALESCE(#{completed},completed_at),
          last_http_code=#{http},last_outcome=#{outcome},next_poll_at=#{next},poll_stopped=#{stopped},stop_reason=#{reason},refreshing_until=NULL
        WHERE request_id=#{id} AND refreshing_until=#{lease}
        """)
    void observe(@Param("id") String id,@Param("lease") OffsetDateTime lease,@Param("status") String status,
        @Param("now") OffsetDateTime now,@Param("completed") OffsetDateTime completed,@Param("http") Integer http,
        @Param("outcome") String outcome,@Param("next") OffsetDateTime next,@Param("stopped") boolean stopped,@Param("reason") String reason);
    @Update("UPDATE background_checks SET poll_stopped=TRUE,stop_reason=#{reason} WHERE request_id=#{id}")
    void stop(@Param("id") String id,@Param("reason") String reason);
    @Insert("""
        INSERT INTO background_check_views(request_id,viewed_by,action,status,outcome) VALUES(#{id},#{actor},#{action},#{status},#{outcome})
        """) void view(@Param("id") String id,@Param("actor") long actor,@Param("action") String action,
            @Param("status") String status,@Param("outcome") String outcome);
    @Select("""
        SELECT v.id,v.viewed_at,v.viewed_by,a.username,v.action,v.status,v.outcome
        FROM background_check_views v JOIN user_accounts a ON a.id=v.viewed_by
        WHERE v.request_id=#{id} ORDER BY v.viewed_at DESC,v.id DESC LIMIT 100
        """) List<Map<String,Object>> views(String id);
}
