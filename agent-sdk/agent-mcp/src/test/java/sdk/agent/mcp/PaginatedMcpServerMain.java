package sdk.agent.mcp;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import sdk.agent.json.Json;

/// A deliberately raw JSON-RPC stdio peer for discovery lifecycle integration tests. It handles
/// requests concurrently so a blocked `tools/list` can be released through an ordinary `tools/call`.
public final class PaginatedMcpServerMain {

    private enum Scenario { TWO_PAGE, CYCLE, CAP, REFRESH, CLOSE }

    private final Scenario scenario;
    private final Path pidFile;
    private final AtomicInteger listCalls = new AtomicInteger();
    private final AtomicInteger activeLists = new AtomicInteger();
    private final AtomicInteger peakLists = new AtomicInteger();
    private final CountDownLatch refreshEntered = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final CountDownLatch readerClosed = new CountDownLatch(1);
    private final CountDownLatch secondRefresh = new CountDownLatch(1);
    private volatile int generation;
    private BufferedWriter output;

    private PaginatedMcpServerMain(Scenario scenario, Path pidFile) {
        this.scenario = scenario;
        this.pidFile = pidFile;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("expected scenario and PID file");
        new PaginatedMcpServerMain(Scenario.valueOf(args[0]), Path.of(args[1])).serve();
    }

    private void serve() throws Exception {
        Files.writeString(pidFile, Long.toString(ProcessHandle.current().pid()), StandardCharsets.UTF_8);
        ExecutorService handlers = Executors.newCachedThreadPool();
        try (var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
             var out = new BufferedWriter(new OutputStreamWriter(System.out, StandardCharsets.UTF_8))) {
            output = out;
            for (String line; (line = input.readLine()) != null; ) {
                Json message = Json.parse(line);
                handlers.submit(() -> handle((Json.Obj) message));
            }
        } finally {
            readerClosed.countDown();
            handlers.shutdown();
            if (!handlers.awaitTermination(2, TimeUnit.SECONDS)) handlers.shutdownNow();
        }
    }

    private void handle(Json.Obj request) {
        try {
            String method = string(request, "method");
            switch (method) {
                case "initialize" -> initialized(request);
                case "tools/list" -> listTools(request);
                case "tools/call" -> callTool(request);
                default -> { /* client notifications need no response */ }
            }
        } catch (Exception e) {
            System.err.println("fixture request failed: " + e);
        }
    }

    private void initialized(Json.Obj request) throws IOException {
        reply(request, Json.obj(
                "protocolVersion", Json.str("2025-06-18"),
                "capabilities", Json.obj("tools", Json.obj("listChanged", Json.bool(true))),
                "serverInfo", Json.obj("name", Json.str("paged"), "version", Json.str("1"))));
    }
    private void listTools(Json.Obj request) throws Exception {
        int page = listCalls.incrementAndGet();
        if (scenario == Scenario.TWO_PAGE) {
            reply(request, tools(page == 1 ? List.of("first") : List.of("second"), page == 1 ? "next" : null));
            return;
        }
        if (scenario == Scenario.CYCLE) {
            reply(request, tools(List.of("first"), "loop"));
            return;
        }
        if (scenario == Scenario.CAP) {
            reply(request, tools(List.of("page_" + page), "page_" + page));
            return;
        }
        if (page == 1) {
            reply(request, tools(List.of("set", "await", "release", "stats", "latest_" + generation), null));
            return;
        }

        int active = activeLists.incrementAndGet();
        peakLists.accumulateAndGet(active, Math::max);
        refreshEntered.countDown();
        try {
            release.await();
            if (scenario == Scenario.CLOSE) readerClosed.await();
            reply(request, tools(List.of("set", "await", "release", "stats", "latest_" + generation), null));
            if (page >= 3) secondRefresh.countDown();
        } finally {
            activeLists.decrementAndGet();
        }
    }

    private void callTool(Json.Obj request) throws Exception {
        Json.Obj params = object(request, "params");
        String name = string(params, "name");
        switch (name) {
            case "set" -> {
                Json.Obj arguments = object(params, "arguments");
                generation = ((Json.Num) arguments.get("generation").orElseThrow()).value().intValueExact();
                notifyToolsChanged();
                reply(request, text("set"));
            }
            case "await" -> {
                refreshEntered.await();
                reply(request, text("blocked"));
            }
            case "release" -> {
                release.countDown();
                reply(request, text("released"));
            }
            case "stats" -> {
                if (scenario == Scenario.REFRESH) secondRefresh.await();
                reply(request, text(Json.obj("lists", Json.num(listCalls.get()),
                        "peak", Json.num(peakLists.get())).toText()));
            }
            default -> reply(request, text("ok"));
        }
    }

    private static Json.Obj text(String text) {
        return Json.obj("content", Json.arr(Json.obj("type", Json.str("text"), "text", Json.str(text))),
                "isError", Json.bool(false));
    }

    private static Json.Obj tools(List<String> names, String nextCursor) {
        var toolList = names.stream().map(PaginatedMcpServerMain::tool).toList();
        var values = new java.util.LinkedHashMap<String, Json>();
        values.put("tools", Json.arr(toolList));
        if (nextCursor != null) values.put("nextCursor", Json.str(nextCursor));
        return Json.obj(values);
    }

    private static Json.Obj tool(String name) {
        return Json.obj("name", Json.str(name), "description", Json.str(name),
                "inputSchema", Json.obj("type", Json.str("object"), "properties", Json.Obj.EMPTY));
    }

    private void notifyToolsChanged() throws IOException {
        send(Json.obj("jsonrpc", Json.str("2.0"),
                "method", Json.str("notifications/tools/list_changed"), "params", Json.Obj.EMPTY));
    }

    private void reply(Json.Obj request, Json result) throws IOException {
        send(Json.obj("jsonrpc", Json.str("2.0"), "id", request.get("id").orElseThrow(), "result", result));
    }

    private synchronized void send(Json.Obj message) throws IOException {
        output.write(message.toText());
        output.newLine();
        output.flush();
    }

    private static Json.Obj object(Json.Obj object, String key) {
        return (Json.Obj) object.get(key).orElseThrow();
    }

    private static String string(Json.Obj object, String key) {
        return ((Json.Str) object.get(key).orElseThrow()).value();
    }
}
