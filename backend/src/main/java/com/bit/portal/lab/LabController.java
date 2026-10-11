package com.bit.portal.lab;

import com.bit.portal.auth.PortalPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.Map;
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
    public LabController(LabRunner runner) { this.runner=runner; }
    @GetMapping("/capabilities") public Map<String,Object> capabilities() { return runner.capabilities(); }
    @PostMapping(value="/experiments",produces="application/x-ndjson")
    public StreamingResponseBody execute(@AuthenticationPrincipal PortalPrincipal principal,
            @Valid @RequestBody LabRequest request,HttpServletRequest servletRequest,HttpServletResponse response) {
        // Servlet streaming has its own timer, independent of the external attempt and run deadlines.
        // Let the bounded runner stop first and flush its final event. This affects only this Lab request.
        var async=WebAsyncUtils.getAsyncManager(servletRequest).getAsyncWebRequest();
        async.setTimeout((long)request.durationMs()+5000L);
        var control=runner.reserve(principal.accountId(),request);
        async.addTimeoutHandler(()->control.stop("STREAM_TIMEOUT"));
        response.setHeader("Cache-Control","no-store");
        return output->runner.execute(control,request,output);
    }
    @PostMapping("/experiments/{id}/cancel")
    public Map<String,String> cancel(@AuthenticationPrincipal PortalPrincipal principal,@PathVariable String id) {
        runner.cancel(id,principal.accountId());return Map.of("status","CANCELLING");
    }
}
