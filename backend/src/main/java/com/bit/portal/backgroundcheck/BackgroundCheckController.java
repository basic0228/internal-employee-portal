package com.bit.portal.backgroundcheck;

import com.fasterxml.jackson.databind.JsonNode;
import com.bit.portal.auth.PortalPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("portal-dev")
@RequestMapping("/api/admin/employees/{id}/background-checks")
public class BackgroundCheckController {
    private final BackgroundCheckService service;
    private final BackgroundCheckHistoryService history;
    public BackgroundCheckController(BackgroundCheckService service,BackgroundCheckHistoryService history) { this.service=service;this.history=history; }
    @GetMapping public JsonNode list(@PathVariable String id) { return service.read(id,null); }
    @GetMapping("/{checkId}") public JsonNode detail(@PathVariable String id,@PathVariable String checkId,
            @AuthenticationPrincipal PortalPrincipal actor) { return history.detail(id,checkId,actor.accountId()); }
    @GetMapping("/history") public Map<String,Object> history(@PathVariable String id,@RequestParam(defaultValue="0") int offset) {
        if(offset<0 || offset>1000000) throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,"페이지 범위를 확인하세요.");
        return history.history(id,offset);
    }
    @PostMapping public BackgroundCheckHistory create(@PathVariable String id,@AuthenticationPrincipal PortalPrincipal actor,
            @Valid @RequestBody CreateBackgroundCheckRequest request) { return history.create(id,actor.accountId(),request); }
    @PostMapping("/history/{request}/refresh") public Map<String,Object> refresh(@PathVariable String id,@PathVariable String request,
            @AuthenticationPrincipal PortalPrincipal actor,@RequestBody Refresh requestBody) {
        return history.refresh(id,request,actor.accountId(),requestBody.automatic());
    }
    @PostMapping("/history/{request}/resolve") public BackgroundCheckHistory resolve(@PathVariable String id,@PathVariable String request,
            @AuthenticationPrincipal PortalPrincipal actor,@Valid @RequestBody Resolve requestBody) {
        return history.resolve(id,request,actor.accountId(),requestBody.checkId(),requestBody.confirmed());
    }
    @GetMapping("/history/{request}/candidates") public JsonNode candidates(@PathVariable String id,@PathVariable String request) {
        return history.candidates(id,request);
    }
    public record Refresh(boolean automatic) { }
    public record Resolve(@NotBlank @Pattern(regexp="[A-Za-z0-9._-]{1,128}") String checkId,boolean confirmed) { }
}
