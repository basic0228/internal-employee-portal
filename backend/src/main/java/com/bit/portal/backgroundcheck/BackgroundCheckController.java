package com.bit.portal.backgroundcheck;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("portal-dev")
@RequestMapping("/api/admin/employees/{id}/background-checks")
public class BackgroundCheckController {
    private final BackgroundCheckService service;
    public BackgroundCheckController(BackgroundCheckService service) { this.service=service; }
    @GetMapping public JsonNode list(@PathVariable String id) { return service.read(id,null); }
    @GetMapping("/{checkId}") public JsonNode detail(@PathVariable String id,@PathVariable String checkId) { return service.read(id,checkId); }
}