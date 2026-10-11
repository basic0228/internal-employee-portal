package com.bit.portal.lab.store;

import java.util.List;
import org.apache.ibatis.annotations.*;

public interface LabRecordingMapper {
    @Insert("INSERT INTO lab_campaigns(campaign_id,owner_account_id,plan_json) VALUES(#{campaignId},#{ownerAccountId},#{planJson})")
    int insertCampaign(LabStoredCampaign campaign);
    @Select("SELECT campaign_id,owner_account_id,plan_json FROM lab_campaigns WHERE campaign_id=#{id} AND owner_account_id=#{owner}")
    LabStoredCampaign campaign(@Param("id") String id,@Param("owner") long owner);
    @Select("SELECT campaign_id FROM lab_campaigns WHERE campaign_id=#{id} AND owner_account_id=#{owner} FOR UPDATE")
    String lockCampaign(@Param("id") String id,@Param("owner") long owner);
    @Select("SELECT campaign_id,owner_account_id,plan_json FROM lab_campaigns WHERE owner_account_id=#{owner} ORDER BY created_at DESC LIMIT 100")
    List<LabStoredCampaign> campaigns(long owner);
    @Insert("""
        INSERT INTO lab_runs(run_id,owner_account_id,campaign_id,case_id,instance_id,status,planned_calls,observed_calls,snapshot_json)
        VALUES(#{runId},#{ownerAccountId},#{campaignId},#{caseId},#{instanceId},#{status},#{plannedCalls},#{observedCalls},#{snapshotJson})
        """)
    int insertRun(LabStoredRun run);
    @Update("""
        UPDATE lab_runs SET status=#{status},observed_calls=#{observedCalls},snapshot_json=#{snapshotJson},updated_at=CURRENT_TIMESTAMP
        WHERE run_id=#{runId} AND owner_account_id=#{ownerAccountId}
        """)
    int updateRun(LabStoredRun run);
    @Select("""
        SELECT run_id,owner_account_id,campaign_id,case_id,instance_id,status,planned_calls,observed_calls,snapshot_json
        FROM lab_runs WHERE run_id=#{id} AND owner_account_id=#{owner}
        """)
    LabStoredRun run(@Param("id") String id,@Param("owner") long owner);
    @Select("""
        SELECT run_id,owner_account_id,campaign_id,case_id,instance_id,status,planned_calls,observed_calls,snapshot_json
        FROM lab_runs WHERE campaign_id=#{id} AND owner_account_id=#{owner} ORDER BY created_at,run_id
        """)
    List<LabStoredRun> campaignRuns(@Param("id") String id,@Param("owner") long owner);
    @Select("""
        SELECT run_id,owner_account_id,campaign_id,case_id,instance_id,status,planned_calls,observed_calls,snapshot_json
        FROM lab_runs WHERE owner_account_id=#{owner} ORDER BY updated_at DESC LIMIT 100
        """)
    List<LabStoredRun> recentRuns(long owner);
}
