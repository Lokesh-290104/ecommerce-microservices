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
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Runs the repo's real shell scripts (scripts/build.sh and the MySQL init script, copied
 * unmodified) against stub mvnw / docker / wsl.exe / mysql on a PATH that contains only the
 * stubs, so no real Maven, Docker or MySQL is touched. Each stub appends "name args" to
 * calls.log. Lives in one module only; it tests repo-level scripts.
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
        // GIT_BASH overrides the default install location.
        String override = System.getenv("GIT_BASH");
        Path candidate = override != null && !override.isBlank() ? Path.of(override)
                : WINDOWS ? Path.of("C:\\Program Files\\Git\\bin\\bash.exe") : Path.of("/bin/bash");
        assumeTrue(Files.isExecutable(candidate),
                "bash not found at " + candidate + "; set GIT_BASH to run the build.sh tests");
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
    void gitBashWithoutLocalDaemonFallsBackToWsl() throws Exception {
        assumeTrue(WINDOWS, "the WSL branch needs Git Bash's `pwd -W`");
        stub(bin.resolve("docker"), "docker", 1); // docker CLI present but `docker info` fails
        stub(bin.resolve("wsl.exe"), "wsl.exe", 0);

        Result r = run();

        assertEquals(0, r.exit, r.stderr);
        assertEquals(List.of("mvnw -B package", "docker info", "wsl.exe --cd " + windowsPath(repo)
                + " docker compose build"), lowerWslPath(r.calls));
    }

    @Test
    void gitBashWithoutDockerCliUsesWsl() throws Exception {
        assumeTrue(WINDOWS, "the WSL branch needs Git Bash's `pwd -W`");
        stub(bin.resolve("wsl.exe"), "wsl.exe", 0);

        Result r = run();

        assertEquals(0, r.exit, r.stderr);
        assertEquals(List.of("mvnw -B package", "wsl.exe --cd " + windowsPath(repo) + " docker compose build"),
                lowerWslPath(r.calls));
    }

    @Test
    void realBashNeverTakesTheWslBranch() throws Exception {
        // Inside WSL, wsl.exe is on PATH via interop but `pwd -W` does not exist: report the
        // local daemon's own error instead of running `wsl.exe --cd ""`.
        assumeFalse(WINDOWS, "Git Bash supports `pwd -W`");
        stub(bin.resolve("docker"), "docker", 1);
        stub(bin.resolve("wsl.exe"), "wsl.exe", 0);

        Result r = run();

        assertEquals(1, r.exit);
        assertTrue(r.stderr.contains("No usable Docker daemon"), r.stderr);
        assertEquals(List.of("mvnw -B package", "docker info", "docker info"), r.calls);
    }

    @Test
    void neitherDockerNorWslFailsWithMessage() throws Exception {
        Result r = run();

        assertEquals(1, r.exit);
        assertTrue(r.stderr.contains("No usable Docker daemon (tried docker, and wsl.exe from Git Bash)."), r.stderr);
        assertEquals(List.of("mvnw -B package"), r.calls);
    }

    @Test
    void mysqlInitRejectsPasswordsThatBreakTheSqlLiteral() throws Exception {
        // null = variable unset; the guard (not bash's `unbound variable`) must name it.
        for (String bad : java.util.Arrays.asList("it's", "back\\slash", "", null)) {
            Result r = runInit(bad);
            assertEquals(1, r.exit, "password [" + bad + "]");
            assertTrue(r.stderr.contains("ORDER_SVC_DB_PASSWORD must be non-empty"), r.stderr);
            assertTrue(r.stderr.contains("docker compose down -v"), r.stderr);
            assertEquals(List.of(), r.calls, "mysql must not run");
        }
    }

    @Test
    void mysqlInitRunsSqlForValidPasswords() throws Exception {
        Result r = runInit("order_svc_dev_pw");

        assertEquals(0, r.exit, r.stderr);
        assertEquals(1, r.calls.size(), r.calls.toString());
        assertTrue(r.calls.get(0).startsWith("mysql --protocol=socket -uroot"), r.calls.toString());
    }

    /** Runs the real MySQL init script with a stub mysql; only ORDER_SVC_DB_PASSWORD varies. */
    private Result runInit(String orderPassword) throws Exception {
        Files.deleteIfExists(repo.resolve("calls.log"));
        Path script = repo.resolve("01-databases.sh");
        Files.copy(ROOT.resolve("docker/mysql/init/01-databases.sh"), script,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        stub(bin.resolve("mysql"), "mysql", 0);
        ProcessBuilder pb = new ProcessBuilder(bash, script.toString()).directory(repo.toFile());
        Map<String, String> env = pb.environment();
        env.put("PATH", bin.toString());
        env.remove("Path");
        env.put("MYSQL_ROOT_PASSWORD", "root_dev_pw");
        env.put("USER_SVC_DB_PASSWORD", "user_svc_dev_pw");
        env.put("PRODUCT_SVC_DB_PASSWORD", "product_svc_dev_pw");
        if (orderPassword == null) {
            env.remove("ORDER_SVC_DB_PASSWORD");
        } else {
            env.put("ORDER_SVC_DB_PASSWORD", orderPassword);
        }
        env.put("PAYMENT_SVC_DB_PASSWORD", "payment_svc_dev_pw");
        return finish(pb);
    }

    /** What Git Bash's `pwd -W` prints for dir: the Windows path with forward slashes. */
    private static String windowsPath(Path dir) throws IOException {
        return dir.toRealPath().toString().replace('\\', '/').toLowerCase();
    }

    /** Drive letter / short-name casing can differ between Java and Git Bash; compare wsl.exe lines lowercased. */
    private static List<String> lowerWslPath(List<String> calls) {
        return calls.stream().map(c -> c.startsWith("wsl.exe --cd ") ? c.toLowerCase() : c).toList();
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
        return finish(pb);
    }

    private Result finish(ProcessBuilder pb) throws Exception {
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
