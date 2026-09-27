package dev.lokesh.shop.user;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs the real scripts/build.sh (copied, unmodified) against stub mvnw / docker / wsl.exe on a
 * PATH that contains only the stubs, so no real Maven or Docker is touched. Each stub appends
 * "name args" to calls.log. Lives in one module only; it tests a repo-level script.
 */
class BuildScriptTests {

    private static final Path ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final boolean WINDOWS = File.separatorChar == '\\';

    @TempDir
    Path repo;
    Path bin;
    String bash;

    @BeforeEach
    void setUp() throws IOException {
        // On Windows plain "bash" may be WSL's System32\bash.exe; use Git Bash explicitly.
        Path candidate = WINDOWS ? Path.of("C:\\Program Files\\Git\\bin\\bash.exe") : Path.of("/bin/bash");
        assumeTrue(Files.isExecutable(candidate), "bash not available at " + candidate);
        bash = candidate.toString();

        Files.createDirectories(repo.resolve("scripts"));
        Files.copy(ROOT.resolve("scripts/build.sh"), repo.resolve("scripts/build.sh"));
        bin = Files.createDirectories(repo.resolve("stubbin"));
        stub(repo.resolve("mvnw"), "mvnw", 0);
        // build.sh calls dirname; provide it without putting the real /usr/bin on PATH.
        write(bin.resolve("dirname"), "#!/bin/bash\ncase \"$1\" in */*) echo \"${1%/*}\";; *) echo .;; esac\n");
    }

    @Test
    void localDockerBuildsImagesAndPassesMavenArgsThrough() throws Exception {
        stub(bin.resolve("docker"), "docker", 0);
        stub(bin.resolve("wsl.exe"), "wsl.exe", 0);

        Result r = run("-DskipTests", "-q");

        assertEquals(0, r.exit, r.stderr);
        assertEquals(List.of(
                "mvnw -B package -DskipTests -q",
                "docker info",
                "docker compose build"), r.calls);
    }

    @Test
    void noLocalDaemonFallsBackToWsl() throws Exception {
        stub(bin.resolve("docker"), "docker", 1); // docker CLI present but `docker info` fails
        stub(bin.resolve("wsl.exe"), "wsl.exe", 0);

        Result r = run();

        assertEquals(0, r.exit, r.stderr);
        assertEquals(3, r.calls.size(), r.calls.toString());
        assertEquals("mvnw -B package", r.calls.get(0));
        assertEquals("docker info", r.calls.get(1));
        String wsl = r.calls.get(2);
        assertTrue(wsl.startsWith("wsl.exe --cd ") && wsl.endsWith(" docker compose build"), wsl);
        if (WINDOWS) {
            // `pwd -W` is Git Bash only: the Windows form of the repo dir, which WSL can translate.
            String cd = wsl.substring("wsl.exe --cd ".length(), wsl.length() - " docker compose build".length());
            assertEquals(repo.toRealPath().toString().replace('\\', '/').toLowerCase(), cd.toLowerCase());
        }
    }

    @Test
    void dockerCliMissingAndWslPresentUsesWsl() throws Exception {
        stub(bin.resolve("wsl.exe"), "wsl.exe", 0);

        Result r = run();

        assertEquals(0, r.exit, r.stderr);
        assertEquals("mvnw -B package", r.calls.get(0));
        assertTrue(r.calls.get(1).startsWith("wsl.exe --cd ") && r.calls.get(1).endsWith(" docker compose build"),
                r.calls.toString());
    }

    @Test
    void neitherDockerNorWslFailsWithMessage() throws Exception {
        Result r = run();

        assertEquals(1, r.exit);
        assertTrue(r.stderr.contains("No Docker daemon found (tried docker and wsl.exe)."), r.stderr);
        assertEquals(List.of("mvnw -B package"), r.calls);
    }

    @Test
    void mavenFailureStopsBeforeDocker() throws Exception {
        stub(repo.resolve("mvnw"), "mvnw", 3);
        stub(bin.resolve("docker"), "docker", 0);

        Result r = run();

        assertEquals(3, r.exit);
        assertEquals(List.of("mvnw -B package"), r.calls);
    }

    private record Result(int exit, String stderr, List<String> calls) {
    }

    private Result run(String... mavenArgs) throws Exception {
        List<String> cmd = new java.util.ArrayList<>(List.of(bash, "scripts/build.sh"));
        cmd.addAll(List.of(mavenArgs));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(repo.toFile());
        pb.environment().put("PATH", bin.toString());
        pb.environment().remove("Path"); // Windows keeps a case-variant copy
        Path err = repo.resolve("stderr.txt");
        pb.redirectError(err.toFile()).redirectOutput(ProcessBuilder.Redirect.DISCARD);
        Process p = pb.start();
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "build.sh timed out");
        Path log = repo.resolve("calls.log");
        List<String> calls = Files.exists(log) ? Files.readAllLines(log) : List.of();
        return new Result(p.exitValue(), Files.readString(err), calls);
    }

    /** A stub that logs "name args" to calls.log (in build.sh's cwd, the repo root) and exits. */
    private static void stub(Path file, String name, int exit) throws IOException {
        write(file, "#!/bin/bash\necho \"" + name + " $*\" >> calls.log\nexit " + exit + "\n");
    }

    private static void write(Path file, String content) throws IOException {
        Files.writeString(file, content, StandardCharsets.UTF_8);
        file.toFile().setExecutable(true);
    }
}
