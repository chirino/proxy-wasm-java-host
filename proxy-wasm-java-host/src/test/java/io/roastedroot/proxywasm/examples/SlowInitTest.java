package io.roastedroot.proxywasm.examples;

import com.dylibso.chicory.compiler.MachineFactoryCompiler;
import com.dylibso.chicory.runtime.Instance;
import com.dylibso.chicory.wasm.Parser;
import com.dylibso.chicory.wasm.WasmModule;
import io.roastedroot.proxywasm.StartException;
import io.roastedroot.proxywasm.internal.ProxyWasm;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 */
public class SlowInitTest {
    private static final WasmModule module =
            Parser.parse(Path.of("./src/test/go-examples/coraza/coraza-proxy-wasm.wasm"));

    @Test
    public void testSlow() throws StartException, IOException {
        testConfig("/slow-config.json");
    }

    @Test
    public void testFast() throws StartException, IOException {
        testConfig("/fast-config.json");
    }

    public void testConfig(String reosurce) throws StartException, IOException {

        String config;
        try (InputStream is = SlowInitTest.class.getResourceAsStream(reosurce)) {
            config = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }

        var handler = new MockHandler();
        handler.setPluginConfig(config);

        Instance.Builder instanceBuilder =
                Instance.builder(module).withMachineFactory(MachineFactoryCompiler::compile);
        try (var host =
                ProxyWasm.builder()
                        .withPluginHandler(handler)
                        .withStart(false)
                        .build(instanceBuilder)) {

            long startTime = System.nanoTime();
            host.start();
            long endTime = System.nanoTime();
            long durationInNanos = endTime - startTime;
            System.out.printf(
                    "%s: Host start() execution time: %.3f ms%n",
                    reosurce, durationInNanos / 1_000_000.0);
        }
    }
}
