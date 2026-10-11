package com.bit.portal.lab;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.*;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Test-only API. Never package this as an application endpoint or treat its data as external measurements. */
public final class MockBackgroundApi implements AutoCloseable {
    private final HttpServer server;
    private final ExecutorService threads=Executors.newFixedThreadPool(6);
    private final ObjectMapper json=new ObjectMapper();
    private final ConcurrentMap<String,Map<String,Object>> checks=new ConcurrentHashMap<>();
    public final AtomicInteger calls=new AtomicInteger(), posts=new AtomicInteger(), inflight=new AtomicInteger(), maxInflight=new AtomicInteger();
    public volatile String mode="NORMAL";
    public MockBackgroundApi(int port) throws IOException {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",port),0);
        server.setExecutor(threads);server.createContext("/background-checks",this::handle);server.start();
    }
    public String baseUrl() { return "http://127.0.0.1:"+server.getAddress().getPort(); }
    private void handle(HttpExchange x) throws IOException {
        calls.incrementAndGet();int live=inflight.incrementAndGet();maxInflight.accumulateAndGet(live,Math::max);
        try {
            if("UNAUTHORIZED".equals(mode)) { send(x,401,Map.of("error","Unauthorized"));return; }
            if("OVERLOAD".equals(mode)) { x.getResponseHeaders().add("Retry-After","2");send(x,503,Map.of("retryAfter",3));return; }
            if("NOT_FOUND".equals(mode)) { send(x,404,Map.of("error","Not Found"));return; }
            if("INTERNAL_ERROR".equals(mode)) { send(x,500,Map.of("error","Internal Server Error"));return; }
            if("BROKEN".equals(mode)) { sendRaw(x,200,"not-json");return; }
            if("LARGE".equals(mode)) { sendRaw(x,200,"x".repeat(270000));return; }
            if("SLOW".equals(mode) || "SLOW_POST".equals(mode) && "POST".equals(x.getRequestMethod())) sleep(350);
            if("CONCURRENCY".equals(mode)) sleep(100);
            if("BODY_STALL".equals(mode)) {
                x.sendResponseHeaders(200,10); x.getResponseBody().write('a');x.getResponseBody().flush();sleep(2000);return;
            }
            String path=x.getRequestURI().getPath();
            if("POST".equals(x.getRequestMethod())) {
                posts.incrementAndGet();
                var input=json.readTree(x.getRequestBody());
                String id="CHK-mock-"+posts.get();
                var item=new ConcurrentHashMap<String,Object>();
                item.put("checkId",id);item.put("employeeId",input.path("employeeId").asText());item.put("status","pending");
                item.put("firstName",input.path("firstName").asText());item.put("lastName",input.path("lastName").asText());
                item.put("dateOfBirth",input.path("dateOfBirth").asText());item.put("createdAt",Instant.now().minusSeconds(1).toString());
                item.put("estimatedCompletionSeconds",1);
                checks.put(id,item);send(x,201,item);return;
            }
            if(path.equals("/background-checks")) {
                String employee=x.getRequestURI().getRawQuery()==null?"":x.getRequestURI().getRawQuery().replace("employeeId=","");
                var list=checks.values().stream().filter(c->employee.equals(c.get("employeeId"))).map(this::safeList).toList();
                send(x,200,Map.of("employeeId",employee,"checks",list,"totalCount",list.size()));return;
            }
            String id=path.substring(path.lastIndexOf('/')+1);
            Map<String,Object> item=checks.get(id);
            if(item==null) item=new HashMap<>(Map.of("checkId",id,"employeeId","EMP-001","status","clear",
                    "createdAt","2026-01-01T00:00:00Z","completedAt","2026-01-01T00:00:01Z","firstName","MOCK_PRIVATE_NAME","criminalRecord",false));
            else {
                item.put("status","clear");item.put("completedAt",Instant.now().toString());
            }
            send(x,200,item);
        } finally { inflight.decrementAndGet();x.close(); }
    }
    private Map<String,Object> safeList(Map<String,Object> input) {
        var out=new HashMap<String,Object>();
        for(String key:List.of("checkId","status","createdAt","completedAt")) if(input.containsKey(key)) out.put(key,input.get(key));
        return out;
    }
    private void send(HttpExchange x,int code,Object body) throws IOException { sendRaw(x,code,json.writeValueAsString(body)); }
    private void sendRaw(HttpExchange x,int code,String body) throws IOException {
        byte[] bytes=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);x.getResponseHeaders().add("Content-Type","application/json");
        x.sendResponseHeaders(code,bytes.length);x.getResponseBody().write(bytes);
    }
    private void sleep(int ms) { try { Thread.sleep(ms); } catch(InterruptedException e) { Thread.currentThread().interrupt(); } }
    public void close() { server.stop(0);threads.shutdownNow(); }
}
