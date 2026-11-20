package ai.varai.lensjourney.boundary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.varai.lensjourney.AttestationPayload;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;

/**
 * A release app written in JAVA that depends on the core artifact only. Java is the case the Kotlin-only boundary could not
 * cover: Kotlin {@code internal} is public in the bytecode, so a Java caller or reflection could reach it. This artifact has no
 * such member to reach. Each "refused" test compiles real Java source with javac against the core artifact; the reflection tests
 * walk every class in the built jar.
 */
class CoreOnlyJavaConsumerTest {
    private static final String CORE_CP = System.getProperty("boundary.coreOnlyClasspath");

    private static String compile(String className, String source) throws IOException {
        Path dir = Files.createTempDirectory("java-snippet");
        try {
            Path file = dir.resolve(className + ".java");
            Files.writeString(file, source);
            JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
            java.io.ByteArrayOutputStream err = new java.io.ByteArrayOutputStream();
            int code = javac.run(null, null, err, "-d", dir.toString(), "-cp", CORE_CP, file.toString());
            return (code == 0 ? "COMPILED" : "FAILED") + "\n" + err.toString("UTF-8");
        } finally {
            try (var walk = Files.walk(dir)) {
                walk.sorted(Collections.reverseOrder()).map(Path::toFile).forEach(File::delete);
            }
        }
    }

    private static void assertRefused(String result, String... expected) {
        assertTrue(result.startsWith("FAILED"), "javac accepted Java code that must be refused:\n" + result);
        for (String text : expected) assertTrue(result.contains(text), "expected javac to say '" + text + "', it said:\n" + result);
    }

    @Test
    void controlOrdinaryJavaAgainstTheCoreCompiles() throws IOException {
        String result = compile("Ok",
            "import ai.varai.lensjourney.AttestationPayload;\n"
          + "public class Ok { public static AttestationPayload make() { return new AttestationPayload.PlayIntegrity(\"token\"); } }\n");
        assertTrue(result.startsWith("COMPILED"), "the control must compile, otherwise the refusals prove nothing:\n" + result);
    }

    @Test
    void javaCannotNameADebugPayload() throws IOException {
        assertRefused(compile("A",
            "import ai.varai.lensjourney.AttestationPayload;\n"
          + "public class A { Object x = new AttestationPayload.Debug(\"c\"); }\n"), "cannot find symbol", "Debug");
    }

    @Test
    void javaCannotNameARestrictedPayload() throws IOException {
        assertRefused(compile("B",
            "import ai.varai.lensjourney.AttestationPayload;\n"
          + "import java.util.Map;\n"
          + "public class B { Object x = new AttestationPayload.Restricted(\"origin\", \"debug\", Map.of(\"challenge\", \"c\")); }\n"),
            "cannot find symbol", "Restricted");
    }

    @Test
    void javaCannotNameADebugProviderInEitherPackage() throws IOException {
        assertRefused(compile("C",
            "public class C { Object x = new ai.varai.lensjourney.DebugAttestationProvider(); }\n"), "cannot find symbol", "DebugAttestationProvider");
        assertRefused(compile("D",
            "public class D { Object x = new ai.varai.lensjourney.debug.DebugAttestationProvider(); }\n"), "does not exist", "debug");
    }

    @Test
    void javaCannotSubclassThePayloadToSmuggleInAnotherKind() throws IOException {
        String result = compile("E",
            "import ai.varai.lensjourney.AttestationPayload;\n"
          + "public class E extends AttestationPayload { }\n");
        assertTrue(result.startsWith("FAILED"), "a Java subclass of the sealed payload class must not compile:\n" + result);
    }

    @Test
    void reflectionFindsNoDebugOrRestrictedTypeToLoad() {
        for (String name : List.of(
                "ai.varai.lensjourney.AttestationPayload$Debug",
                "ai.varai.lensjourney.AttestationPayload$Restricted",
                "ai.varai.lensjourney.DebugAttestationProvider",
                "ai.varai.lensjourney.debug.DebugAttestationProvider",
                "ai.varai.lensjourney.debug.DebugAttestation")) {
            assertThrows(ClassNotFoundException.class, () -> Class.forName(name), name);
        }
    }

    /** Walks every class in the built core jar: the payload kinds are exactly the two platform proofs, and no class, constructor,
     *  method or field anywhere is named for debug or a restricted payload. There is nothing to construct or call. */
    @Test
    void reflectionOverEveryCoreClassFindsNothingDebugShaped() throws Exception {
        File jar = null;
        for (String entry : CORE_CP.split(File.pathSeparator)) {
            File f = new File(entry);
            if (f.getName().startsWith("lens-journey-android-") && f.getName().endsWith(".jar")) jar = f;
        }
        assertTrue(jar != null, "the core jar is on the classpath: " + CORE_CP);

        List<String> classNames = new ArrayList<>();
        try (JarFile jf = new JarFile(jar)) {
            for (var e = jf.entries(); e.hasMoreElements(); ) {
                JarEntry entry = e.nextElement();
                if (entry.getName().endsWith(".class") && !entry.getName().equals("module-info.class")) {
                    classNames.add(entry.getName().replace('/', '.').replaceAll("\\.class$", ""));
                }
            }
        }
        assertTrue(classNames.size() > 20, "the walk saw the core classes: " + classNames.size());

        URL[] urls = Arrays.stream(CORE_CP.split(File.pathSeparator)).map(p -> {
            try { return new File(p).toURI().toURL(); } catch (Exception ex) { throw new RuntimeException(ex); }
        }).toArray(URL[]::new);
        List<String> offenders = new ArrayList<>();
        TreeSet<String> payloadKinds = new TreeSet<>();
        try (URLClassLoader loader = new URLClassLoader(urls, null)) {
            Class<?> payload = loader.loadClass(AttestationPayload.class.getName());
            for (String name : classNames) {
                Class<?> c = loader.loadClass(name);
                if (isDebugShaped(c.getName())) offenders.add("class " + c.getName());
                for (Constructor<?> k : c.getDeclaredConstructors()) {
                    for (Class<?> p : k.getParameterTypes()) if (isDebugShaped(p.getName())) offenders.add("constructor " + c.getName() + " takes " + p.getName());
                }
                for (Method m : c.getDeclaredMethods()) if (isDebugShaped(m.getName())) offenders.add("method " + c.getName() + "." + m.getName());
                for (Field f : c.getDeclaredFields()) if (isDebugShaped(f.getName())) offenders.add("field " + c.getName() + "." + f.getName());
                if (payload.isAssignableFrom(c) && c != payload) payloadKinds.add(c.getSimpleName());
            }
        }
        assertEquals(new ArrayList<String>(), offenders, "nothing in the core artifact may be named for debug attestation");
        assertEquals(new TreeSet<>(List.of("AppAttest", "PlayIntegrity")), payloadKinds, "the payload kinds an app can obtain");
        assertFalse(payloadKinds.contains("Restricted"));
    }

    private static boolean isDebugShaped(String name) {
        String n = name.toLowerCase();
        return n.contains("debug") || n.contains("restricted");
    }
}
