import com.sun.source.util.JavacTask;
import javax.tools.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

/** Parse-only check: deliberately does not claim Android API compilation. */
public final class CheckJavaSyntax {
    public static void main(String[] arguments) throws Exception {
        Path root = Paths.get(arguments.length == 0 ? "app/src/main/java" : arguments[0]);
        List<java.io.File> files = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(path -> path.toString().endsWith(".java")).sorted().forEach(path -> files.add(path.toFile()));
        }
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("JDK 17 is required");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager manager = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            JavacTask task = (JavacTask) compiler.getTask(null, manager, diagnostics,
                    Arrays.asList("--release", "17", "-proc:none"), null, manager.getJavaFileObjectsFromFiles(files));
            task.parse();
        }
        int errors = 0;
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
            if (diagnostic.getKind() != Diagnostic.Kind.ERROR) continue;
            errors++;
            System.err.println(diagnostic.getSource().getName() + ":" + diagnostic.getLineNumber() + ": " + diagnostic.getMessage(Locale.ROOT));
        }
        System.out.println("Java syntax: " + files.size() + " files, " + errors + " errors (parse only, not Android compilation)");
        if (errors > 0) System.exit(1);
    }
}
