package com.bit.portal.backgroundcheck;

import com.bit.portal.backgroundcheck.client.BackgroundCheckClient;
import com.bit.portal.employee.EmployeeService;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@Profile("portal-dev")
public class BackgroundCheckService {
    private final BackgroundCheckClient client;
    private final EmployeeService employees;
    private final String key;
    private final boolean enabled;
    private final int connectMs, requestMs;
    public BackgroundCheckService(BackgroundCheckClient client, EmployeeService employees,
            @Value("${background-check.candidate-key:}") String key,
            @Value("${background-check.enabled:false}") boolean enabled,
            @Value("${background-check.client.connect-timeout-ms:0}") int connectMs,
            @Value("${background-check.client.request-timeout-ms:0}") int requestMs) {
        this.client=client;this.employees=employees;this.key=key;this.enabled=enabled;this.connectMs=connectMs;this.requestMs=requestMs;
    }
    public JsonNode read(String id,String checkId) {
        employees.get(id);
        if(!enabled || !client.permitted() || key.isBlank() || connectMs<=0 || requestMs<=0 || connectMs>30000 || requestMs>60000)
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"배경 조회는 실측 후 설정 확인이 필요합니다.");
        if(checkId!=null && !checkId.matches("[A-Za-z0-9._-]{1,128}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"조회 번호를 확인하세요.");
        try(var transport=client.open(connectMs)) {
            var reply=transport.exchange(checkId==null?"LIST":"DETAIL",id,checkId,null,key,requestMs,
                    System.nanoTime()+java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(requestMs),()->false);
            if(!"HTTP_RESPONSE".equals(reply.outcome()) || !Integer.valueOf(200).equals(reply.httpCode()) || reply.body()==null || !reply.body().isObject())
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"외부 조회 결과를 확인할 수 없습니다.");
            if(!id.equals(reply.body().path("employeeId").asText()) || checkId!=null && !checkId.equals(reply.body().path("checkId").asText()))
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,"해당 직원의 조회 결과가 아닙니다.");
            if(checkId==null && !reply.body().path("checks").isArray() || checkId!=null && !java.util.Set.of("pending","clear","flagged").contains(reply.body().path("status").asText()))
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"외부 응답 형식을 확인할 수 없습니다.");
            return reply.body();
        }
    }
}
