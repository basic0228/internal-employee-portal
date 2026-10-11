package com.bit.portal.lab;

import com.bit.portal.auth.PortalPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.Map;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.WebAsyncUtils;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@Profile("portal-dev")
@RequestMapping("/api/admin/reliability-lab")
public class LabController {
    private final LabRunner runner;
    private final LabRecordingService recordings;
    public LabController(LabRunner runner,LabRecordingService recordings) { this.runner=runner;this.recordings=recordings; }
    @GetMapping("/capabilities") public Map<String,Object> capabilities() { return runner.capabilities(); }
    @PostMapping(value="/experiments",produces="application/x-ndjson")
    public StreamingResponseBody execute(@AuthenticationPrincipal PortalPrincipal principal,
            @Valid @RequestBody LabRequest request,HttpServletRequest servletRequest,HttpServletResponse response,
            @RequestHeader(value="X-Lab-Campaign",required=false) String campaignId,
            @RequestHeader(value="X-Lab-Case",required=false) String caseId) {
        // Servlet streaming has its own timer, independent of the external attempt and run deadlines.
        // Let the bounded runner stop first and flush its final event. This affects only this Lab request.
        var async=WebAsyncUtils.getAsyncManager(servletRequest).getAsyncWebRequest();
        async.setTimeout((long)request.durationMs()+5000L);
        var control=runner.reserve(principal.accountId(),request,campaignId,caseId);
        async.addTimeoutHandler(()->control.stop("STREAM_TIMEOUT"));
        response.setHeader("Cache-Control","no-store");
        return output->runner.execute(control,request,output);
    }
    @PostMapping("/campaigns") public Map<String,Object> createCampaign(@AuthenticationPrincipal PortalPrincipal principal,
            @Valid @RequestBody LabCampaignRequest request) { return recordings.create(principal.accountId(),request); }
    @GetMapping("/campaigns") public Object campaigns(@AuthenticationPrincipal PortalPrincipal principal) { return recordings.campaigns(principal.accountId()); }
    @GetMapping("/campaigns/{id}") public Map<String,Object> campaign(@AuthenticationPrincipal PortalPrincipal principal,@PathVariable String id) {
        return recordings.campaign(principal.accountId(),id);
    }
    @GetMapping("/recordings") public List<Map<String,Object>> recordings(@AuthenticationPrincipal PortalPrincipal principal) { return recordings.recent(principal.accountId()); }
    @GetMapping("/recordings/{id}") public Map<String,Object> recording(@AuthenticationPrincipal PortalPrincipal principal,@PathVariable String id) {
        return recordings.run(principal.accountId(),id);
    }
    @PostMapping("/experiments/{id}/cancel")
    public Map<String,String> cancel(@AuthenticationPrincipal PortalPrincipal principal,@PathVariable String id) {
        runner.cancel(id,principal.accountId());return Map.of("status","CANCELLING");
    }
}
