package org.javacs;

import com.fasterxml.jackson.databind.JsonNode;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.code.Symbol;
import io.typefox.lsapi.*;
import io.typefox.lsapi.Diagnostic;

import javax.tools.*;
import java.io.*;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.javacs.Main.JSON;

class JavaLanguageServer implements LanguageServer {
    private static final Logger LOG = Logger.getLogger("main");
    private Path workspaceRoot;
    private NotificationCallback<PublishDiagnosticsParams> publishDiagnostics = p -> {};
    private NotificationCallback<MessageParams> showMessage = m -> {};
    private Map<Path, String> sourceByPath = new HashMap<>();

    public JavaLanguageServer() {
        this.testJavac = Optional.empty();
    }

    public JavaLanguageServer(JavacHolder testJavac) {
        this.testJavac = Optional.of(testJavac);
    }

    public void onError(String message, Throwable error) {
        if (error instanceof ShowMessageException)
            showMessage.call(((ShowMessageException) error).message);
        else {
            MessageParamsImpl m = new MessageParamsImpl();

            m.setMessage(message);
            m.setType(MessageParams.TYPE_ERROR);

            showMessage.call(m);
        }
    }

    @Override
    public InitializeResult initialize(InitializeParams params) {
        workspaceRoot = Paths.get(params.getRootPath());

        InitializeResultImpl result = new InitializeResultImpl();

        ServerCapabilitiesImpl c = new ServerCapabilitiesImpl();

        // TODO incremental mode
        c.setTextDocumentSync(ServerCapabilities.SYNC_INCREMENTAL);
        c.setDefinitionProvider(true);
        c.setCompletionProvider(new CompletionOptionsImpl());
        c.setHoverProvider(true);

        result.setCapabilities(c);

        return result;
    }

    @Override
    public void shutdown() {

    }

    @Override
    public void exit() {

    }

    @Override
    public TextDocumentService getTextDocumentService() {
        return new TextDocumentService() {
            @Override
            public List<? extends CompletionItem> completion(TextDocumentPositionParams position) {
                return autocomplete(position);
            }

            @Override
            public CompletionItem resolveCompletionItem(CompletionItem unresolved) {
                return null;
            }

            @Override
            public Hover hover(TextDocumentPositionParams position) {
                return doHover(position);
            }

            @Override
            public SignatureHelp signatureHelp(TextDocumentPositionParams position) {
                return null;
            }

            @Override
            public List<? extends Location> definition(TextDocumentPositionParams position) {
                return gotoDefinition(position);
            }

            @Override
            public List<? extends Location> references(ReferenceParams params) {
                return null;
            }

            @Override
            public DocumentHighlight documentHighlight(TextDocumentPositionParams position) {
                return null;
            }

            @Override
            public List<? extends SymbolInformation> documentSymbol(DocumentSymbolParams params) {
                return null;
            }

            @Override
            public List<? extends Command> codeAction(CodeActionParams params) {
                return null;
            }

            @Override
            public List<? extends CodeLens> codeLens(CodeLensParams params) {
                return null;
            }

            @Override
            public CodeLens resolveCodeLens(CodeLens unresolved) {
                return null;
            }

            @Override
            public List<? extends TextEdit> formatting(DocumentFormattingParams params) {
                return null;
            }

            @Override
            public List<? extends TextEdit> rangeFormatting(DocumentRangeFormattingParams params) {
                return null;
            }

            @Override
            public List<? extends TextEdit> onTypeFormatting(DocumentOnTypeFormattingParams params) {
                return null;
            }

            @Override
            public WorkspaceEdit rename(RenameParams params) {
                return null;
            }

            @Override
            public void didOpen(DidOpenTextDocumentParams params) {
                TextDocumentItem document = params.getTextDocument();
                URI uri = URI.create(document.getUri());
                Optional<Path> path = getFilePath(uri);

                if (path.isPresent()) {
                    String text = document.getText();

                    sourceByPath.put(path.get(), text);

                    doLint(path.get());
                }
            }

            @Override
            public void didChange(DidChangeTextDocumentParams params) {
                VersionedTextDocumentIdentifier document = params.getTextDocument();
                URI uri = URI.create(document.getUri());
                Optional<Path> path = getFilePath(uri);

                if (path.isPresent()) {
                    for (TextDocumentContentChangeEvent change : params.getContentChanges()) {
                        if (change.getRange() == null)
                            sourceByPath.put(path.get(), change.getText());
                        else {
                            String existingText = sourceByPath.get(path.get());
                            String newText = patch(existingText, change);

                            sourceByPath.put(path.get(), newText);

                            LOG.info(path.get().toString());
                            LOG.info(change.getText());
                            LOG.info(newText);
                        }
                    }
                }
            }

            @Override
            public void didClose(DidCloseTextDocumentParams params) {
                // remove from sourceByPath???
            }

            @Override
            public void didSave(DidSaveTextDocumentParams params) {
                TextDocumentIdentifier document = params.getTextDocument();
                URI uri = URI.create(document.getUri());
                Optional<Path> path = getFilePath(uri);

                if (path.isPresent())
                    doLint(path.get());
            }

            @Override
            public void onPublishDiagnostics(NotificationCallback<PublishDiagnosticsParams> callback) {
                publishDiagnostics = callback;
            }
        };
    }

    private String patch(String sourceText, TextDocumentContentChangeEvent change) {
        try {
            Range range = change.getRange();
            BufferedReader reader = new BufferedReader(new StringReader(sourceText));
            StringWriter writer = new StringWriter();

            // Skip unchanged lines
            int line = 0;

            while (line < range.getStart().getLine()) {
                writer.write(reader.readLine() + '\n');
                line++;
            }

            // Skip unchanged chars
            for (int character = 0; character < range.getStart().getCharacter(); character++)
                writer.write(reader.read());

            // Write replacement text
            writer.write(change.getText());

            // Skip replaced text
            reader.skip(change.getRangeLength());

            // Write remaining text
            while (true) {
                int next = reader.read();

                if (next == -1)
                    return writer.toString();
                else
                    writer.write(next);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private Optional<Path> getFilePath(URI uri) {
        if (!uri.getScheme().equals("file"))
            return Optional.empty();
        else
            return Optional.of(Paths.get(uri.getPath()));
    }

    private void doLint(Path path) {
        List<PublishDiagnosticsParamsImpl> errors = lint(path);

        errors.forEach(publishDiagnostics::call);
    }

    @Override
    public WorkspaceService getWorkspaceService() {
        return new WorkspaceService() {
            @Override
            public List<? extends SymbolInformation> symbol(WorkspaceSymbolParams params) {
                return null;
            }

            @Override
            public void didChangeConfiguraton(DidChangeConfigurationParams params) {

            }

            @Override
            public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {

            }
        };
    }

    @Override
    public WindowService getWindowService() {
        return new WindowService() {
            @Override
            public void onShowMessage(NotificationCallback<MessageParams> callback) {
                showMessage = callback;
            }

            @Override
            public void onShowMessageRequest(NotificationCallback<ShowMessageRequestParams> callback) {

            }

            @Override
            public void onLogMessage(NotificationCallback<MessageParams> callback) {

            }
        };
    }

    public List<PublishDiagnosticsParamsImpl> lint(Path path) {
        LOG.info("Lint " + path);

        DiagnosticCollector<JavaFileObject> errors = new DiagnosticCollector<>();

        JavacHolder compiler = findCompiler(path);
        JavaFileObject file = findFile(compiler, path);

        compiler.onError(errors);
        compiler.compile(compiler.parse(file));

        Map<URI, PublishDiagnosticsParamsImpl> files = new HashMap<>();
        
        files.put(path.toUri(), newPublishDiagnostics(path.toUri()));
        
        errors.getDiagnostics().forEach(error -> {
            if (error.getStartPosition() != javax.tools.Diagnostic.NOPOS) {
                URI uri = error.getSource().toUri();
                PublishDiagnosticsParamsImpl publish = files.computeIfAbsent(uri, this::newPublishDiagnostics);

                RangeImpl range = position(error);
                DiagnosticImpl diagnostic = new DiagnosticImpl();

                diagnostic.setSeverity(Diagnostic.SEVERITY_ERROR);
                diagnostic.setRange(range);
                diagnostic.setCode(error.getCode());
                diagnostic.setMessage(error.getMessage(null));

                publish.getDiagnostics().add(diagnostic);
            }
        });

        List<PublishDiagnosticsParamsImpl> result = new ArrayList<>();

        result.addAll(files.values());

        return result;
    }
    
    private PublishDiagnosticsParamsImpl newPublishDiagnostics(URI newUri) {
        PublishDiagnosticsParamsImpl p = new PublishDiagnosticsParamsImpl();

        p.setDiagnostics(new ArrayList<>());
        p.setUri(newUri.toString());

        return p;
    }

    private Map<JavacConfig, JavacHolder> compilerCache = new HashMap<>();

    /**
     * Instead of looking for javaconfig.json and creating a JavacHolder, just use this.
     * For testing.
     */
    private final Optional<JavacHolder> testJavac;

    /**
     * Look for a configuration in a parent directory of uri
     */
    private JavacHolder findCompiler(Path path) {
        if (testJavac.isPresent())
            return testJavac.get();

        Path dir = path.getParent();
        Optional<JavacConfig> config = findConfig(dir);
        Optional<JavacHolder> maybeHolder = config.map(c -> compilerCache.computeIfAbsent(c, this::newJavac));

        return maybeHolder.orElseThrow(() -> {
            MessageParamsImpl message = new MessageParamsImpl();

            message.setMessage("Can't find configuration file for " + path);
            message.setType(MessageParams.TYPE_WARNING);

            return new ShowMessageException(message, null);
        });
    }

    private JavacHolder newJavac(JavacConfig c) {
        return new JavacHolder(c.classPath,
                               c.sourcePath,
                               c.outputDirectory);
    }

    // TODO invalidate cache when VSCode notifies us config file has changed
    private Map<Path, Optional<JavacConfig>> configCache = new HashMap<>();

    private Optional<JavacConfig> findConfig(Path dir) {
        return configCache.computeIfAbsent(dir, this::doFindConfig);
    }

    private Optional<JavacConfig> doFindConfig(Path dir) {
        while (true) {
            Optional<JavacConfig> found = readIfConfig(dir);

            if (found.isPresent())
                return found;
            else if (workspaceRoot.startsWith(dir))
                return Optional.empty();
            else
                dir = dir.getParent();
        }
    }

    /**
     * If directory contains a config file, for example javaconfig.json or an eclipse project file, read it.
     */
    private Optional<JavacConfig> readIfConfig(Path dir) {
        if (Files.exists(dir.resolve("javaconfig.json"))) {
            JavaConfigJson json = readJavaConfigJson(dir.resolve("javaconfig.json"));
            Path classPathFilePath = dir.resolve(json.classPathFile);
            Set<Path> classPath = readClassPathFile(classPathFilePath);
            Set<Path> sourcePath = json.sourcePath.stream().map(dir::resolve).collect(Collectors.toSet());
            Path outputDirectory = dir.resolve(json.outputDirectory);
            JavacConfig config = new JavacConfig(sourcePath, classPath, outputDirectory);

            return Optional.of(config);
        }
        // TODO add more file types
        else {
            return Optional.empty();
        }
    }

    private JavaConfigJson readJavaConfigJson(Path configFile) {
        try {
            return JSON.readValue(configFile.toFile(), JavaConfigJson.class);
        } catch (IOException e) {
            MessageParamsImpl message = new MessageParamsImpl();

            message.setMessage("Error reading " + configFile);
            message.setType(MessageParams.TYPE_ERROR);

            throw new ShowMessageException(message, e);
        }
    }

    private Set<Path> readClassPathFile(Path classPathFilePath) {
        try {
            InputStream in = Files.newInputStream(classPathFilePath);
            String text = new BufferedReader(new InputStreamReader(in))
                    .lines()
                    .collect(Collectors.joining());
            Path dir = classPathFilePath.getParent();

            return Arrays.stream(text.split(":"))
                         .map(dir::resolve)
                         .collect(Collectors.toSet());
        } catch (IOException e) {
            MessageParamsImpl message = new MessageParamsImpl();

            message.setMessage("Error reading " + classPathFilePath);
            message.setType(MessageParams.TYPE_ERROR);

            throw new ShowMessageException(message, e);
        }
    }

    private JavaFileObject findFile(JavacHolder compiler, Path path) {
        if (sourceByPath.containsKey(path))
            return new StringFileObject(sourceByPath.get(path), path);
        else
            return compiler.fileManager.getRegularFile(path.toFile());
    }

    private RangeImpl position(javax.tools.Diagnostic<? extends JavaFileObject> error) {
        // Compute start position
        PositionImpl start = new PositionImpl();

        start.setLine((int) (error.getLineNumber() - 1));
        start.setCharacter((int) (error.getColumnNumber() - 1));

        // Compute end position
        PositionImpl end = endPosition(error);

        // Combine into Range
        RangeImpl range = new RangeImpl();

        range.setStart(start);
        range.setEnd(end);

        return range;
    }

    private PositionImpl endPosition(javax.tools.Diagnostic<? extends JavaFileObject> error) {
        try (Reader reader = error.getSource().openReader(true)) {
            long startOffset = error.getStartPosition();
            long endOffset = error.getEndPosition();

            reader.skip(startOffset);

            int line = (int) error.getLineNumber() - 1;
            int column = (int) error.getColumnNumber() - 1;

            for (long i = startOffset; i < endOffset; i++) {
                int next = reader.read();

                if (next == '\n') {
                    line++;
                    column = 0;
                }
                else
                    column++;
            }

            PositionImpl end = new PositionImpl();

            end.setLine(line);
            end.setCharacter(column);

            return end;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public List<LocationImpl> gotoDefinition(TextDocumentPositionParams position) {
        Optional<Path> maybePath = getFilePath(URI.create(position.getTextDocument().getUri()));

        if (maybePath.isPresent()) {
            Path path = maybePath.get();
            DiagnosticCollector<JavaFileObject> errors = new DiagnosticCollector<>();
            JavacHolder compiler = findCompiler(path);
            JavaFileObject file = findFile(compiler, path);
            long cursor = findOffset(file, position.getPosition().getLine(), position.getPosition().getCharacter());
            SymbolUnderCursorVisitor visitor = new SymbolUnderCursorVisitor(file, cursor, compiler.context);

            compiler.afterAnalyze(visitor);
            compiler.onError(errors);
            compiler.compile(compiler.parse(file));

            List<LocationImpl> result = new ArrayList<>();
            ClassIndex index = compiler.context.get(ClassIndex.class);
            Optional<SymbolLocation> maybeLocate = visitor.found.flatMap(s -> index.locate(s));
            
            if (maybeLocate.isPresent()) {
                SymbolLocation locate = maybeLocate.get();
                URI uri = locate.file.toUri();
                Path symbolPath = Paths.get(uri);
                JavaFileObject symbolFile = findFile(compiler, symbolPath);
                RangeImpl range = findPosition(symbolFile, locate.startPosition, locate.endPosition);
                LocationImpl location = new LocationImpl();

                location.setRange(range);
                location.setUri(uri.toString());

                result.add(location);
            }

            return result;
        }
        else return Collections.emptyList();
    }

    private static RangeImpl findPosition(JavaFileObject file, long startOffset, long endOffset) {
        try (Reader in = file.openReader(true)) {
            long offset = 0;
            int line = 0;
            int character = 0;

            // Find the start position
            while (offset < startOffset) {
                int next = in.read();

                if (next < 0)
                    break;
                else {
                    offset++;
                    character++;

                    if (next == '\n') {
                        line++;
                        character = 0;
                    }
                }
            }

            PositionImpl start = createPosition(line, character);

            // Find the end position
            while (offset < endOffset) {
                int next = in.read();

                if (next < 0)
                    break;
                else {
                    offset++;
                    character++;

                    if (next == '\n') {
                        line++;
                        character = 0;
                    }
                }
            }

            PositionImpl end = createPosition(line, character);

            // Combine into range
            RangeImpl range = new RangeImpl();

            range.setStart(start);
            range.setEnd(end);

            return range;
        } catch (IOException e) {
            throw ShowMessageException.error(e.getMessage(), e);
        }
    }

    private static PositionImpl createPosition(int line, int character) {
        PositionImpl p = new PositionImpl();

        p.setLine(line);
        p.setCharacter(character);

        return p;
    }

    private static long findOffset(JavaFileObject file, int targetLine, int targetCharacter) {
        try (Reader in = file.openReader(true)) {
            long offset = 0;
            int line = 0;
            int character = 0;

            while (line < targetLine) {
                int next = in.read();

                if (next < 0)
                    return offset;
                else {
                    offset++;

                    if (next == '\n')
                        line++;
                }
            }

            while (character < targetCharacter) {
                int next = in.read();

                if (next < 0)
                    return offset;
                else {
                    offset++;
                    character++;
                }
            }

            return offset;
        } catch (IOException e) {
            throw ShowMessageException.error(e.getMessage(), e);
        }
    }
    
    public HoverImpl doHover(TextDocumentPositionParams position) {
        HoverImpl result = new HoverImpl();
        
        Optional<Path> maybePath = getFilePath(URI.create(position.getTextDocument().getUri()));

        if (maybePath.isPresent()) {
            Path path = maybePath.get();
            DiagnosticCollector<JavaFileObject> errors = new DiagnosticCollector<>();
            JavacHolder compiler = findCompiler(path);
            JavaFileObject file = findFile(compiler, path);
            long cursor = findOffset(file, position.getPosition().getLine(), position.getPosition().getCharacter());
            SymbolUnderCursorVisitor visitor = new SymbolUnderCursorVisitor(file, cursor, compiler.context);

            compiler.afterAnalyze(visitor);
            compiler.onError(errors);
            compiler.compile(compiler.parse(file));
            
            if (visitor.found.isPresent()) {
                Symbol symbol = visitor.found.get();
                List<MarkedStringImpl> contents = new ArrayList<>();

                switch (symbol.getKind()) {
                    case PACKAGE:
                        contents.add(markedString("package " + symbol.getQualifiedName()));

                        break;
                    case ENUM:
                        contents.add(markedString("enum " + symbol.getQualifiedName()));

                        break;
                    case CLASS:
                        contents.add(markedString("class " + symbol.getQualifiedName()));

                        break;
                    case ANNOTATION_TYPE:
                        contents.add(markedString("@interface " + symbol.getQualifiedName()));

                        break;
                    case INTERFACE:
                        contents.add(markedString("interface " + symbol.getQualifiedName()));

                        break;
                    case METHOD:
                    case CONSTRUCTOR:
                    case STATIC_INIT:
                    case INSTANCE_INIT:
                        Symbol.MethodSymbol method = (Symbol.MethodSymbol) symbol;
                        String signature = AutocompleteVisitor.methodSignature(method);
                        String returnType = ShortTypePrinter.print(method.getReturnType());
                        
                        contents.add(markedString(returnType + " " + signature));

                        break;
                    case PARAMETER:
                    case LOCAL_VARIABLE:
                    case EXCEPTION_PARAMETER:
                    case ENUM_CONSTANT:
                    case FIELD:
                        contents.add(markedString(symbol.type + " " + symbol.getSimpleName()));

                        break;
                    case TYPE_PARAMETER:
                    case OTHER:
                    case RESOURCE_VARIABLE:
                        break;
                }
                
                result.setContents(contents);
            }
        }
        
        return result;
    }

    private MarkedStringImpl markedString(String value) {
        MarkedStringImpl result = new MarkedStringImpl();

        result.setLanguage("java");
        result.setValue(value);

        return result;
    }

    public List<CompletionItemImpl> autocomplete(TextDocumentPositionParams position) {
        Optional<Path> maybePath = getFilePath(URI.create(position.getTextDocument().getUri()));

        if (maybePath.isPresent()) {
            Path path = maybePath.get();
            DiagnosticCollector<JavaFileObject> errors = new DiagnosticCollector<>();
            JavacHolder compiler = findCompiler(path);
            JavaFileObject file = findFile(compiler, path);
            long cursor = findOffset(file, position.getPosition().getLine(), position.getPosition().getCharacter());
            JavaFileObject withSemi = withSemicolonAfterCursor(file, path, cursor);
            AutocompleteVisitor autocompleter = new AutocompleteVisitor(withSemi, cursor, compiler.context);

            compiler.afterAnalyze(autocompleter);
            compiler.onError(errors);

            JCTree.JCCompilationUnit ast = compiler.parse(withSemi);

            // Remove all statements after the cursor
            // There are often parse errors after the cursor, which can generate unrecoverable type errors
            ast.accept(new AutocompletePruner(withSemi, cursor, compiler.context));

            compiler.compile(ast);

            return autocompleter.suggestions;
        }
        else return Collections.emptyList();
    }

    /**
     * Insert ';' after the users cursor so we recover from parse errors in a helpful way when doing autocomplete.
     */
    private JavaFileObject withSemicolonAfterCursor(JavaFileObject file, Path path, long cursor) {
        try (Reader reader = file.openReader(true)) {
            StringBuilder acc = new StringBuilder();

            for (int i = 0; i < cursor; i++) {
                int next = reader.read();

                if (next == -1)
                    throw new RuntimeException("End of file " + file + " before cursor " + cursor);

                acc.append((char) next);
            }

            acc.append(";");

            for (int next = reader.read(); next > 0; next = reader.read()) {
                acc.append((char) next);
            }

            return new StringFileObject(acc.toString(), path);
        } catch (IOException e) {
            throw ShowMessageException.error("Error reading " + file, e);
        }
    }

    public JsonNode echo(JsonNode echo) {
        return echo;
    }
}
