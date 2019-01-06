package org.javacs;

import com.google.gson.*;
import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.sun.source.doctree.DocCommentTree;
import com.sun.source.doctree.DocTree;
import com.sun.source.doctree.ParamTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.TreePath;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.tools.Diagnostic;
import javax.tools.JavaFileObject;
import org.javacs.lsp.*;

class JavaLanguageServer extends LanguageServer {
    private static final Logger LOG = Logger.getLogger("main");

    // TODO allow multiple workspace roots
    private Path workspaceRoot;
    private SourcePath sourcePath;
    private final LanguageClient client;
    private Set<String> externalDependencies = Set.of();
    private Set<Path> classPath = Set.of();

    JavaCompilerService compiler;
    private final Map<URI, VersionedContent> activeDocuments = new HashMap<>();

    private static int severity(Diagnostic.Kind kind) {
        switch (kind) {
            case ERROR:
                return DiagnosticSeverity.Error;
            case WARNING:
            case MANDATORY_WARNING:
                return DiagnosticSeverity.Warning;
            case NOTE:
                return DiagnosticSeverity.Information;
            case OTHER:
            default:
                return DiagnosticSeverity.Hint;
        }
    }

    private static Position position(String content, long offset) {
        int line = 0, column = 0;
        for (int i = 0; i < offset; i++) {
            if (content.charAt(i) == '\n') {
                line++;
                column = 0;
            } else column++;
        }
        return new Position(line, column);
    }

    void publishDiagnostics(Collection<URI> files, List<Diagnostic<? extends JavaFileObject>> javaDiagnostics) {
        var byUri = new HashMap<URI, List<org.javacs.lsp.Diagnostic>>();
        for (var j : javaDiagnostics) {
            if (j.getSource() == null) {
                LOG.warning("No source in warning " + j.getMessage(null));
                continue;
            }
            // Check that error is in an open file
            var uri = j.getSource().toUri();
            if (!files.contains(uri)) {
                LOG.warning(
                        String.format(
                                "Skipped error at %s(%d,%d) because that file isn't open",
                                uri, j.getLineNumber(), j.getColumnNumber()));
                continue;
            }
            // Find start and end position
            var content = contents(uri);
            var start = position(content, j.getStartPosition());
            var end = position(content, j.getEndPosition());
            var d = new org.javacs.lsp.Diagnostic();
            d.severity = severity(j.getKind());
            d.range = new Range(start, end);
            d.code = j.getCode();
            d.message = j.getMessage(null);
            if (j.getCode().equals("unused")) {
                d.tags = List.of(DiagnosticTag.Unnecessary);
            }
            // Add to byUri
            var ds = byUri.computeIfAbsent(uri, __ -> new ArrayList<>());
            ds.add(d);
        }

        for (var f : files) {
            var ds = byUri.getOrDefault(f, List.of());
            var message = new PublishDiagnosticsParams(f, ds);
            client.publishDiagnostics(message);
        }
    }

    void reportErrors(Collection<URI> uris) {
        var messages = compiler.reportErrors(uris);
        publishDiagnostics(uris, messages);
    }

    private static final Gson gson = new Gson();

    private void javaStartProgress(JavaStartProgressParams params) {
        client.customNotification("java/startProgress", gson.toJsonTree(params));
    }

    private void javaReportProgress(JavaReportProgressParams params) {
        client.customNotification("java/reportProgress", gson.toJsonTree(params));
    }

    private void javaEndProgress() {
        client.customNotification("java/endProgress", JsonNull.INSTANCE);
    }

    private JavaCompilerService createCompiler() {
        Objects.requireNonNull(workspaceRoot, "Can't create compiler because workspaceRoot has not been initialized");

        javaStartProgress(new JavaStartProgressParams("Configure javac"));
        javaReportProgress(new JavaReportProgressParams("Finding source roots"));

        // If classpath is specified by the user, don't infer anything
        if (!classPath.isEmpty()) {
            javaEndProgress();
            return new JavaCompilerService(
                    sourcePath.sourceRoots(), sourcePath::allJavaFiles, classPath, Collections.emptySet());
        }
        // Otherwise, combine inference with user-specified external dependencies
        else {
            var infer = new InferConfig(workspaceRoot, externalDependencies);

            javaReportProgress(new JavaReportProgressParams("Inferring class path"));
            var classPath = infer.classPath();

            javaReportProgress(new JavaReportProgressParams("Inferring doc path"));
            var docPath = infer.buildDocPath();

            javaEndProgress();
            return new JavaCompilerService(sourcePath.sourceRoots(), sourcePath::allJavaFiles, classPath, docPath);
        }
    }

    void setExternalDependencies(Set<String> externalDependencies) {
        var changed =
                this.externalDependencies.isEmpty()
                        != externalDependencies.isEmpty(); // TODO shouldn't this be any change?
        this.externalDependencies = externalDependencies;
        if (changed) this.compiler = createCompiler();
    }

    void setClassPath(Set<Path> classPath) {
        var changed = this.classPath.isEmpty() != classPath.isEmpty(); // TODO shouldn't this be any change?
        this.classPath = classPath;
        if (changed) this.compiler = createCompiler();
    }

    @Override
    public InitializeResult initialize(InitializeParams params) {
        this.workspaceRoot = Paths.get(params.rootUri);
        this.sourcePath = new SourcePath(Set.of(workspaceRoot));

        var c = new JsonObject();
        c.addProperty("textDocumentSync", 2); // Incremental
        c.addProperty("hoverProvider", true);
        var completionOptions = new JsonObject();
        completionOptions.addProperty("resolveProvider", true);
        var triggerCharacters = new JsonArray();
        triggerCharacters.add(".");
        completionOptions.add("triggerCharacters", triggerCharacters);
        c.add("completionProvider", completionOptions);
        var signatureHelpOptions = new JsonObject();
        var signatureTrigger = new JsonArray();
        signatureTrigger.add("(");
        signatureTrigger.add(",");
        signatureHelpOptions.add("triggerCharacters", signatureTrigger);
        c.add("signatureHelpProvider", signatureHelpOptions);
        c.addProperty("referencesProvider", true);
        c.addProperty("definitionProvider", true);
        c.addProperty("workspaceSymbolProvider", true);
        c.addProperty("documentSymbolProvider", true);
        c.addProperty("documentFormattingProvider", true);
        var codeLensOptions = new JsonObject();
        codeLensOptions.addProperty("resolveProvider", true);
        c.add("codeLensProvider", codeLensOptions);
        c.addProperty("foldingRangeProvider", true);

        return new InitializeResult(c);
    }

    @Override
    public void initialized() {
        this.compiler = createCompiler();

        // Register for didChangeWatchedFiles notifications
        var options = new JsonObject();
        var watchers = new JsonArray();
        var watchJava = new JsonObject();
        watchJava.addProperty("globPattern", "**/*.java");
        watchers.add(watchJava);
        options.add("watchers", watchers);
        client.registerCapability("workspace/didChangeWatchedFiles", gson.toJsonTree(options));
    }

    @Override
    public void shutdown() {}

    public JavaLanguageServer(LanguageClient client) {
        this.client = client;
    }

    @Override
    public List<SymbolInformation> workspaceSymbols(WorkspaceSymbolParams params) {
        var list = new ArrayList<SymbolInformation>();
        for (var s : compiler.findSymbols(params.query, 50)) {
            var i = asSymbolInformation(s);
            list.add(i);
        }
        return list;
    }

    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams change) {
        var settings = (JsonObject) change.settings;
        var java = settings.getAsJsonObject("java");

        var externalDependencies = java.getAsJsonArray("externalDependencies");
        var strings = new HashSet<String>();
        for (var each : externalDependencies) strings.add(each.getAsString());
        setExternalDependencies(strings);

        var classPath = java.getAsJsonArray("classPath");
        var paths = new HashSet<Path>();
        for (var each : classPath) paths.add(Paths.get(each.getAsString()).toAbsolutePath());
        setClassPath(paths);
    }

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
        // TODO update config when pom.xml changes
        var changed = false;
        var created = new HashSet<Path>();
        var deleted = new HashSet<Path>();
        for (var c : params.changes) {
            if (!isJavaFile(c.uri)) continue;
            var file = Paths.get(c.uri);
            switch (c.type) {
                case FileChangeType.Created:
                    created.add(file);
                    break;
                case FileChangeType.Changed:
                    if (sourcePath.update(file)) changed = true;
                    break;
                case FileChangeType.Deleted:
                    deleted.add(file);
                    break;
            }
        }
        if (sourcePath.create(created)) changed = true;
        if (sourcePath.delete(deleted)) changed = true;
        if (changed) this.compiler = createCompiler();
    }

    private Integer completionItemKind(Element e) {
        switch (e.getKind()) {
            case ANNOTATION_TYPE:
                return CompletionItemKind.Interface;
            case CLASS:
                return CompletionItemKind.Class;
            case CONSTRUCTOR:
                return CompletionItemKind.Constructor;
            case ENUM:
                return CompletionItemKind.Enum;
            case ENUM_CONSTANT:
                return CompletionItemKind.EnumMember;
            case EXCEPTION_PARAMETER:
                return CompletionItemKind.Variable;
            case FIELD:
                return CompletionItemKind.Field;
            case STATIC_INIT:
            case INSTANCE_INIT:
                return CompletionItemKind.Function;
            case INTERFACE:
                return CompletionItemKind.Interface;
            case LOCAL_VARIABLE:
                return CompletionItemKind.Variable;
            case METHOD:
                return CompletionItemKind.Method;
            case PACKAGE:
                return CompletionItemKind.Module;
            case PARAMETER:
                return CompletionItemKind.Variable;
            case RESOURCE_VARIABLE:
                return CompletionItemKind.Variable;
            case TYPE_PARAMETER:
                return CompletionItemKind.TypeParameter;
            case OTHER:
            default:
                return null;
        }
    }

    private boolean isMemberOfObject(Element e) {
        var parent = e.getEnclosingElement();
        if (parent instanceof TypeElement) {
            var type = (TypeElement) parent;
            return type.getQualifiedName().contentEquals("java.lang.Object");
        }
        return false;
    }

    /** Cache of completions from the last call to `completion` */
    private final Map<String, Completion> lastCompletions = new HashMap<>();

    @Override
    public Optional<CompletionList> completion(TextDocumentPositionParams position) {
        // TODO reuse previous compilation when changes are small
        var started = Instant.now();
        var uri = position.textDocument.uri;
        if (!isJavaFile(uri)) return Optional.empty();
        var content = contents(uri);
        var line = position.position.line + 1;
        var column = position.position.character + 1;
        // Figure out what kind of completion we want to do
        var maybeCtx = compiler.parseFile(uri, content).completionContext(line, column);
        // TODO don't complete inside of comments
        if (!maybeCtx.isPresent()) {
            var items = new ArrayList<CompletionItem>();
            for (var name : CompileFocus.TOP_LEVEL_KEYWORDS) {
                var i = new CompletionItem();
                i.label = name;
                i.kind = CompletionItemKind.Keyword;
                i.detail = "keyword";
                items.add(i);
            }
            return Optional.of(new CompletionList(true, items));
        }
        // Compile again, focusing on a region that depends on what type of completion we want to do
        var ctx = maybeCtx.get();
        var focus = compiler.compileFocus(uri, content, ctx.line, ctx.character);
        // Do a specific type of completion
        List<Completion> cs;
        boolean isIncomplete;
        switch (ctx.kind) {
            case MemberSelect:
                cs = focus.completeMembers(false);
                isIncomplete = false;
                break;
            case MemberReference:
                cs = focus.completeMembers(true);
                isIncomplete = false;
                break;
            case Identifier:
                cs = focus.completeIdentifiers(ctx.inClass, ctx.inMethod, ctx.partialName);
                isIncomplete = cs.size() >= CompileFocus.MAX_COMPLETION_ITEMS;
                break;
            case Annotation:
                cs = focus.completeAnnotations(ctx.partialName);
                isIncomplete = cs.size() >= CompileFocus.MAX_COMPLETION_ITEMS;
                break;
            case Case:
                cs = focus.completeCases();
                isIncomplete = false;
                break;
            default:
                throw new RuntimeException("Unexpected completion context " + ctx.kind);
        }
        // Convert to CompletionItem
        var result = new ArrayList<CompletionItem>();
        for (var c : cs) {
            var i = new CompletionItem();
            var id = UUID.randomUUID().toString();
            i.data = new JsonPrimitive(id);
            lastCompletions.put(id, c);
            if (c.element != null) {
                i.label = c.element.getSimpleName().toString();
                i.kind = completionItemKind(c.element);
                // Detailed name will be resolved later, using docs to fill in method names
                if (!(c.element instanceof ExecutableElement)) {
                    i.detail = ShortTypePrinter.print(c.element.asType());
                }
                // TODO prioritize based on usage?
                // TODO prioritize based on scope
                if (isMemberOfObject(c.element)) {
                    i.sortText = 9 + i.label;
                } else {
                    i.sortText = 2 + i.label;
                }
            } else if (c.packagePart != null) {
                i.label = c.packagePart.name;
                i.kind = CompletionItemKind.Module;
                i.detail = c.packagePart.fullName;
                i.sortText = 2 + i.label;
            } else if (c.keyword != null) {
                i.label = c.keyword;
                i.kind = CompletionItemKind.Keyword;
                i.detail = "keyword";
                i.sortText = 3 + i.label;
            } else if (c.className != null) {
                i.label = Parser.lastName(c.className.name);
                i.kind = CompletionItemKind.Class;
                i.detail = c.className.name;
                if (c.className.isImported) {
                    i.sortText = 2 + i.label;
                } else {
                    i.sortText = 4 + i.label;
                }
            } else if (c.snippet != null) {
                i.label = c.snippet.label;
                i.kind = CompletionItemKind.Snippet;
                i.insertText = c.snippet.snippet;
                i.insertTextFormat = InsertTextFormat.Snippet;
                i.sortText = 1 + i.label;
            } else {
                throw new RuntimeException(c + " is not valid");
            }

            result.add(i);
        }
        // Log timing
        var elapsedMs = Duration.between(started, Instant.now()).toMillis();
        if (isIncomplete) LOG.info(String.format("Found %d items (incomplete) in %,d ms", result.size(), elapsedMs));
        else LOG.info(String.format("...found %d items in %,d ms", result.size(), elapsedMs));

        return Optional.of(new CompletionList(isIncomplete, result));
    }

    private Optional<MarkupContent> findDocs(Ptr ptr) {
        LOG.info(String.format("Find docs for `%s`...", ptr));

        // Find el in the doc path
        var file = compiler.docs().find(ptr);
        if (!file.isPresent()) return Optional.empty();
        // Parse file and find el
        var parse = compiler.docs().parse(file.get());
        var path = parse.fuzzyFind(ptr);
        if (!path.isPresent()) return Optional.empty();
        // Parse the doctree associated with el
        var docTree = parse.doc(path.get());
        ;
        var string = asMarkupContent(docTree);
        return Optional.of(string);
    }

    private Optional<String> findMethodDetails(ExecutableElement method) {
        LOG.info(String.format("Find details for method `%s`...", method));

        // TODO find and parse happens twice between findDocs and findMethodDetails
        // Find method in the doc path
        var ptr = new Ptr(method);
        var file = compiler.docs().find(ptr);
        if (!file.isPresent()) return Optional.empty();
        // Parse file and find method
        var parse = compiler.docs().parse(file.get());
        var path = parse.fuzzyFind(ptr);
        if (!path.isPresent()) return Optional.empty();
        // Should be a MethodTree
        var tree = path.get().getLeaf();
        if (!(tree instanceof MethodTree)) {
            LOG.warning(String.format("...method `%s` associated with non-method tree `%s`", method, tree));
            return Optional.empty();
        }
        // Write description of method using info from source
        var methodTree = (MethodTree) tree;
        var args = new StringJoiner(", ");
        for (var p : methodTree.getParameters()) {
            args.add(p.getName());
        }
        var details = String.format("%s %s(%s)", methodTree.getReturnType(), methodTree.getName(), args);
        return Optional.of(details);
    }

    private String defaultDetails(ExecutableElement method) {
        var args = new StringJoiner(", ");
        var missingParamNames =
                method.getParameters().stream().allMatch(p -> p.getSimpleName().toString().matches("arg\\d+"));
        for (var p : method.getParameters()) {
            if (missingParamNames) args.add(ShortTypePrinter.print(p.asType()));
            else args.add(p.getSimpleName().toString());
        }
        return String.format("%s %s(%s)", ShortTypePrinter.print(method.getReturnType()), method.getSimpleName(), args);
    }

    private String asMarkdown(List<? extends DocTree> lines) {
        var join = new StringJoiner("\n");
        for (var l : lines) join.add(l.toString());
        var html = join.toString();
        return Docs.htmlToMarkdown(html);
    }

    private String asMarkdown(DocCommentTree comment) {
        var lines = comment.getFirstSentence();
        return asMarkdown(lines);
    }

    private MarkupContent asMarkupContent(DocCommentTree comment) {
        var markdown = asMarkdown(comment);
        var content = new MarkupContent();
        content.kind = MarkupKind.Markdown;
        content.value = markdown;
        return content;
    }

    @Override
    public CompletionItem resolveCompletionItem(CompletionItem unresolved) {
        if (unresolved.data == null) return unresolved;
        var idJson = (JsonPrimitive) unresolved.data;
        var id = idJson.getAsString();
        var cached = lastCompletions.get(id);
        if (cached == null) {
            LOG.warning("CompletionItem " + id + " was not in the cache");
            return unresolved;
        }
        if (cached.element != null) {
            if (cached.element instanceof ExecutableElement) {
                var method = (ExecutableElement) cached.element;
                unresolved.detail = findMethodDetails(method).orElse(defaultDetails(method));
            }
            var markdown = findDocs(new Ptr(cached.element));
            if (markdown.isPresent()) {
                unresolved.documentation = markdown.get();
            }
        } else if (cached.className != null) {
            var packageName = Parser.mostName(cached.className.name);
            var className = Parser.lastName(cached.className.name);
            var ptr = Ptr.toClass(packageName, className);
            var markdown = findDocs(ptr);
            if (markdown.isPresent()) unresolved.documentation = markdown.get();
        }
        return unresolved;
    }

    private String hoverTypeDeclaration(TypeElement t) {
        var result = new StringBuilder();
        switch (t.getKind()) {
            case ANNOTATION_TYPE:
                result.append("@interface");
                break;
            case INTERFACE:
                result.append("interface");
                break;
            case CLASS:
                result.append("class");
                break;
            case ENUM:
                result.append("enum");
                break;
            default:
                LOG.warning("Don't know what to call type element " + t);
                result.append("???");
        }
        result.append(" ").append(ShortTypePrinter.print(t.asType()));
        var superType = ShortTypePrinter.print(t.getSuperclass());
        switch (superType) {
            case "Object":
            case "none":
                break;
            default:
                result.append(" extends ").append(superType);
        }
        return result.toString();
    }

    private String hoverCode(Element e) {
        if (e instanceof ExecutableElement) {
            var m = (ExecutableElement) e;
            return ShortTypePrinter.printMethod(m);
        } else if (e instanceof VariableElement) {
            var v = (VariableElement) e;
            return ShortTypePrinter.print(v.asType()) + " " + v;
        } else if (e instanceof TypeElement) {
            var t = (TypeElement) e;
            var lines = new StringJoiner("\n");
            lines.add(hoverTypeDeclaration(t) + " {");
            for (var member : t.getEnclosedElements()) {
                // TODO check accessibility
                if (member instanceof ExecutableElement || member instanceof VariableElement) {
                    lines.add("  " + hoverCode(member) + ";");
                } else if (member instanceof TypeElement) {
                    lines.add("  " + hoverTypeDeclaration((TypeElement) member) + " { /* removed */ }");
                }
            }
            lines.add("}");
            return lines.toString();
        } else {
            return e.toString();
        }
    }

    private Optional<String> hoverDocs(Element e) {
        var ptr = new Ptr(e);
        var file = compiler.docs().find(ptr);
        if (!file.isPresent()) return Optional.empty();
        var parse = compiler.docs().parse(file.get());
        var path = parse.fuzzyFind(ptr);
        if (!path.isPresent()) return Optional.empty();
        var doc = parse.doc(path.get());
        var md = asMarkdown(doc);
        return Optional.of(md);
    }

    private CompileFile activeFileCache;
    private int activeFileCacheVersion = -1;

    // TODO take only URI and invalidate based on version
    private void updateActiveFile(URI uri) {
        if (activeFileCache == null || !activeFileCache.file.equals(uri) || activeFileCacheVersion != version(uri)) {
            LOG.info("Recompile active file...");
            activeFileCache = compiler.compileFile(uri, contents(uri));
            activeFileCacheVersion = version(uri);
        }
    }

    @Override
    public Optional<Hover> hover(TextDocumentPositionParams position) {
        // Compile entire file if it's changed since last hover
        var uri = position.textDocument.uri;
        if (!isJavaFile(uri)) return Optional.empty();
        updateActiveFile(uri);

        // Find element undeer cursor
        var line = position.position.line + 1;
        var column = position.position.character + 1;
        var el = activeFileCache.element(line, column);
        if (!el.isPresent()) return Optional.empty();

        // Add code hover message
        var result = new ArrayList<MarkedString>();
        var code = hoverCode(el.get());
        result.add(new MarkedString("java.hover", code));
        // Add docs hover message
        var docs = hoverDocs(el.get());
        if (docs.isPresent()) {
            result.add(new MarkedString("markdown", docs.get()));
        }

        return Optional.of(new Hover(result));
    }

    private SignatureInformation asSignatureInformation(ExecutableElement e) {
        // Figure out parameter info from source or from ExecutableElement
        var i = new SignatureInformation();
        var ptr = new Ptr(e);
        var ps = signatureParamsFromDocs(ptr).orElse(signatureParamsFromMethod(e));
        i.parameters = ps;

        // Compute label from params (which came from either source or ExecutableElement)
        var name = e.getSimpleName();
        if (name.contentEquals("<init>")) name = e.getEnclosingElement().getSimpleName();
        var args = new StringJoiner(", ");
        for (var p : ps) {
            args.add(p.label);
        }
        i.label = name + "(" + args + ")";

        return i;
    }

    private List<ParameterInformation> signatureParamsFromMethod(ExecutableElement e) {
        var missingParamNames = ShortTypePrinter.missingParamNames(e);
        var ps = new ArrayList<ParameterInformation>();
        for (var v : e.getParameters()) {
            var p = new ParameterInformation();
            if (missingParamNames) p.label = ShortTypePrinter.print(v.asType());
            else p.label = v.getSimpleName().toString();
            ps.add(p);
        }
        return ps;
    }

    private Optional<List<ParameterInformation>> signatureParamsFromDocs(Ptr ptr) {
        // Find the file ptr point to, and parse it
        var file = compiler.docs().find(ptr);
        if (!file.isPresent()) return Optional.empty();
        var parse = compiler.docs().parse(file.get());
        // Find the tree
        var path = parse.fuzzyFind(ptr);
        if (!path.isPresent()) return Optional.empty();
        if (!(path.get().getLeaf() instanceof MethodTree)) return Optional.empty();
        var method = (MethodTree) path.get().getLeaf();
        // Find the docstring on method, or empty doc if there is none
        var doc = parse.doc(path.get());
        // Get param docs from @param tags
        var ps = new ArrayList<ParameterInformation>();
        var paramComments = new HashMap<String, String>();
        for (var tag : doc.getBlockTags()) {
            if (tag.getKind() == DocTree.Kind.PARAM) {
                var param = (ParamTree) tag;
                paramComments.put(param.getName().toString(), asMarkdown(param.getDescription()));
            }
        }
        // Get param names from source
        for (var param : method.getParameters()) {
            var info = new ParameterInformation();
            var name = param.getName().toString();
            info.label = name;
            if (paramComments.containsKey(name)) {
                var markdown = paramComments.get(name);
                info.documentation = new MarkupContent("markdown", markdown);
            } else {
                var markdown = Objects.toString(param.getType(), "");
                info.documentation = new MarkupContent("markdown", markdown);
            }
            ps.add(info);
        }
        return Optional.of(ps);
    }

    private SignatureHelp asSignatureHelp(MethodInvocation invoke) {
        // TODO use docs to get parameter names
        var sigs = new ArrayList<SignatureInformation>();
        for (var e : invoke.overloads) {
            sigs.add(asSignatureInformation(e));
        }
        var activeSig = invoke.activeMethod.map(invoke.overloads::indexOf).orElse(0);
        return new SignatureHelp(sigs, activeSig, invoke.activeParameter);
    }

    @Override
    public Optional<SignatureHelp> signatureHelp(TextDocumentPositionParams position) {
        var uri = position.textDocument.uri;
        if (!isJavaFile(uri)) return Optional.empty();
        var content = contents(uri);
        var line = position.position.line + 1;
        var column = position.position.character + 1;
        var focus = compiler.compileFocus(uri, content, line, column);
        var help = focus.methodInvocation().map(this::asSignatureHelp);
        return help;
    }

    @Override
    public Optional<List<Location>> gotoDefinition(TextDocumentPositionParams position) {
        var fromUri = position.textDocument.uri;
        if (!isJavaFile(fromUri)) return Optional.empty();
        var fromLine = position.position.line + 1;
        var fromColumn = position.position.character + 1;

        // Compile from-file and identify element under cursor
        LOG.info(String.format("Go-to-def at %s:%d...", fromUri, fromLine));
        updateActiveFile(fromUri);
        var toEl = activeFileCache.element(fromLine, fromColumn);
        if (!toEl.isPresent()) {
            LOG.info(String.format("...no element at cursor"));
            return Optional.empty();
        }

        // Compile all files that *might* contain definitions of fromEl
        var toFiles = compiler.potentialDefinitions(toEl.get());
        toFiles.add(fromUri);
        var batch = compiler.compileBatch(pruneWord(toFiles, toEl.get()));

        // Find fromEl again, so that we have an Element from the current batch
        var fromElAgain = batch.element(fromUri, fromLine, fromColumn).get();

        // Find all definitions of fromElAgain
        var toTreePaths = batch.definitions(fromElAgain);
        if (!toTreePaths.isPresent()) return Optional.empty();
        var result = new ArrayList<Location>();
        for (var path : toTreePaths.get()) {
            var toUri = path.getCompilationUnit().getSourceFile().toUri();
            var toRange = batch.range(path);
            if (!toRange.isPresent()) {
                LOG.warning(String.format("Couldn't locate `%s`", path.getLeaf()));
                continue;
            }
            var from = new Location(toUri, toRange.get());
            result.add(from);
        }
        return Optional.of(result);
    }

    @Override
    public Optional<List<Location>> findReferences(ReferenceParams position) {
        var toUri = position.textDocument.uri;
        if (!isJavaFile(toUri)) return Optional.empty();
        var toLine = position.position.line + 1;
        var toColumn = position.position.character + 1;

        // Compile from-file and identify element under cursor
        LOG.warning(String.format("Looking for references to %s(%d,%d)...", toUri.getPath(), toLine, toColumn));
        updateActiveFile(toUri);
        var toEl = activeFileCache.element(toLine, toColumn);
        if (!toEl.isPresent()) {
            LOG.warning("...no element under cursor");
            return Optional.empty();
        }

        // Compile all files that *might* contain references to toEl
        var fromFiles = compiler.potentialReferences(toEl.get());
        fromFiles.add(toUri);
        var batch = compiler.compileBatch(pruneWord(fromFiles, toEl.get()));

        // Find toEl again, so that we have an Element from the current batch
        var toElAgain = batch.element(toUri, toLine, toColumn).get();

        // Find all references to toElAgain
        var fromTreePaths = batch.references(toElAgain);
        if (!fromTreePaths.isPresent()) return Optional.empty();
        var result = new ArrayList<Location>();
        for (var path : fromTreePaths.get()) {
            var fromUri = path.getCompilationUnit().getSourceFile().toUri();
            var fromRange = batch.range(path);
            if (!fromRange.isPresent()) {
                LOG.warning(String.format("Couldn't locate `%s`", path.getLeaf()));
                continue;
            }
            var from = new Location(fromUri, fromRange.get());
            result.add(from);
        }
        return Optional.of(result);
    }

    private List<JavaFileObject> pruneWord(Collection<URI> files, Element el) {
        var name = el.getSimpleName().toString();
        if (name.equals("<init>")) name = el.getEnclosingElement().getSimpleName().toString();
        var sources = new ArrayList<JavaFileObject>();
        for (var f : files) {
            var contents = contents(f);
            var pruned = Pruner.prune(f, contents, name);
            sources.add(new StringFileObject(pruned, f));
        }
        return sources;
    }

    private List<JavaFileObject> latestText(Collection<URI> files) {
        var sources = new ArrayList<JavaFileObject>();
        for (var f : files) {
            sources.add(new StringFileObject(contents(f), f));
        }
        return sources;
    }

    private ParseFile cacheParse;
    private URI cacheParseFile = URI.create("file:///NONE");;
    private int cacheParseVersion = -1;

    private void updateCachedParse(URI file) {
        if (file.equals(cacheParseFile) && version(file) == cacheParseVersion) return;
        LOG.info(String.format("Updating cached parse file to %s", file));
        cacheParse = compiler.parseFile(file, contents(file));
        cacheParseFile = file;
        cacheParseVersion = version(file);
    }

    @Override
    public List<SymbolInformation> documentSymbol(DocumentSymbolParams params) {
        var uri = params.textDocument.uri;
        if (!isJavaFile(uri)) return List.of();
        updateCachedParse(uri);
        var paths = cacheParse.documentSymbols();
        var infos = new ArrayList<SymbolInformation>();
        for (var p : paths) {
            infos.add(asSymbolInformation(p));
        }
        return infos;
    }

    static SymbolInformation asSymbolInformation(TreePath path) {
        var i = new SymbolInformation();
        var t = path.getLeaf();
        i.kind = asSymbolKind(t.getKind());
        i.name = symbolName(t);
        i.containerName = containerName(path);
        i.location = Parser.location(path);
        return i;
    }

    private static Integer asSymbolKind(Tree.Kind k) {
        switch (k) {
            case ANNOTATION_TYPE:
            case CLASS:
                return SymbolKind.Class;
            case ENUM:
                return SymbolKind.Enum;
            case INTERFACE:
                return SymbolKind.Interface;
            case METHOD:
                return SymbolKind.Method;
            case TYPE_PARAMETER:
                return SymbolKind.TypeParameter;
            case VARIABLE:
                // This method is used for symbol-search functionality,
                // where we only return fields, not local variables
                return SymbolKind.Field;
            default:
                return null;
        }
    }

    private static String containerName(TreePath path) {
        var parent = path.getParentPath();
        while (parent != null) {
            var t = parent.getLeaf();
            if (t instanceof ClassTree) {
                var c = (ClassTree) t;
                return c.getSimpleName().toString();
            } else if (t instanceof CompilationUnitTree) {
                var c = (CompilationUnitTree) t;
                return Objects.toString(c.getPackageName(), "");
            } else {
                parent = parent.getParentPath();
            }
        }
        return null;
    }

    private static String symbolName(Tree t) {
        if (t instanceof ClassTree) {
            var c = (ClassTree) t;
            return c.getSimpleName().toString();
        } else if (t instanceof MethodTree) {
            var m = (MethodTree) t;
            return m.getName().toString();
        } else if (t instanceof VariableTree) {
            var v = (VariableTree) t;
            return v.getName().toString();
        } else {
            LOG.warning("Don't know how to create SymbolInformation from " + t);
            return "???";
        }
    }

    @Override
    public List<CodeLens> codeLens(CodeLensParams params) {
        // TODO just create a blank code lens on every method, then resolve it async
        var uri = params.textDocument.uri;
        if (!isJavaFile(uri)) return List.of();
        updateCachedParse(uri);
        var declarations = cacheParse.declarations();
        var result = new ArrayList<CodeLens>();
        for (var d : declarations) {
            var range = cacheParse.range(d);
            if (!range.isPresent()) continue;
            var className = JavaCompilerService.className(d);
            var memberName = JavaCompilerService.memberName(d);
            // If test class or method, add "Run Test" code lens
            if (cacheParse.isTestClass(d)) {
                var arguments = new JsonArray();
                arguments.add(uri.toString());
                arguments.add(className);
                arguments.add(JsonNull.INSTANCE);
                var command = new Command("Run All Tests", "java.command.test.run", arguments);
                var lens = new CodeLens(range.get(), command, null);
                result.add(lens);
                // TODO run all tests in file
                // TODO run all tests in package
            }
            if (cacheParse.isTestMethod(d)) {
                var arguments = new JsonArray();
                arguments.add(uri.toString());
                arguments.add(className);
                if (memberName.isPresent()) arguments.add(memberName.get());
                else arguments.add(JsonNull.INSTANCE);
                var command = new Command("Run Test", "java.command.test.run", arguments);
                var lens = new CodeLens(range.get(), command, null);
                result.add(lens);
            }
            if (!cacheParse.isTestMethod(d)) {
                // Unresolved "_ references" code lens
                var start = range.get().start;
                var line = start.line;
                var character = start.character;
                var data = new JsonArray();
                // TODO would textDocument/references do the same thing?
                data.add("java.command.findReferences");
                data.add(uri.toString());
                data.add(line);
                data.add(character);
                var lens = new CodeLens(range.get(), null, data);
                result.add(lens);
            }
        }
        return result;
    }

    @Override
    public CodeLens resolveCodeLens(CodeLens unresolved) {
        // TODO This is pretty klugey, should happen asynchronously after CodeLenses are shown
        if (!recentlyOpened.isEmpty()) {
            reportErrors(recentlyOpened);
            recentlyOpened.clear();
        }
        // Unpack data
        var data = unresolved.data;
        var command = data.get(0).getAsString();
        assert command.equals("java.command.findReferences");
        var uriString = data.get(1).getAsString();
        var uri = URI.create(uriString);
        var line = data.get(2).getAsInt() + 1;
        var character = data.get(3).getAsInt() + 1;
        // Update command
        var count = countReferencesTitle(uri, line, character);
        String title;
        if (count == -1) title = "? references";
        else if (count == 1) title = "1 reference";
        else if (count == 100) title = "Find references";
        else title = String.format("%d references", count);
        var arguments = new JsonArray();
        arguments.add(uri.toString());
        arguments.add(line - 1);
        arguments.add(character - 1);
        unresolved.command = new Command(title, command, arguments);

        return unresolved;
    }

    private int countReferencesTitle(URI toUri, int toLine, int toColumn) {
        LOG.warning(String.format("Count references to %s(%d,%d)...", toUri.getPath(), toLine, toColumn));

        // Compile from-file and identify element under cursor
        updateActiveFile(toUri);
        var toEl = activeFileCache.element(toLine, toColumn);
        if (!toEl.isPresent()) {
            LOG.warning("...no element at code lens");
            return -1;
        }

        // Compile all files that *might* contain references to toEl
        var fromFiles = compiler.potentialReferences(toEl.get());
        fromFiles.add(toUri);

        // If it's too expensive to compute the code lens
        if (fromFiles.size() > 10) return 100;

        // Make sure all fromFiles -> toUri references are in the cache
        updateCountReferencesCache(toUri, fromFiles);

        // Count up how many total references exist in fromFiles
        var toPtr = new Ptr(toEl.get());
        var count = 0;
        for (var from : fromFiles) {
            var cachedFileCounts = countReferencesCache.get(from);
            count += cachedFileCounts.counts.getOrDefault(toPtr, 0);
        }
        return count;
    }

    /** countReferencesCache[file][ptr] is the number of references to ptr in file */
    private Map<URI, CountReferences> countReferencesCache = new HashMap<>();

    private static class CountReferences {
        final Map<Ptr, Integer> counts = new HashMap<>();
        final Instant created = Instant.now();
    }

    /** countReferencesCacheFile is the file pointed to by every ptr in countReferencesCache[_][ptr] */
    private URI countReferencesCacheFile = URI.create("file:///NONE");

    private void updateCountReferencesCache(URI toFile, Collection<URI> fromFiles) {
        // If cached file has changed, invalidate the whole cache
        if (!toFile.equals(countReferencesCacheFile)) {
            LOG.info(String.format("Cache count-references %s", Parser.fileName(toFile)));
            countReferencesCache.clear();
            countReferencesCacheFile = toFile;
        }

        // Figure out which from-files are out-of-date
        var outOfDate = new HashSet<URI>();
        for (var f : fromFiles) {
            Instant modified;
            try {
                modified = Files.getLastModifiedTime(Paths.get(f)).toInstant();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            var expired =
                    !countReferencesCache.containsKey(f) || countReferencesCache.get(f).created.isBefore(modified);
            if (expired) {
                countReferencesCache.remove(f);
                outOfDate.add(f);
            }
        }

        // Compile all out-of-date files
        if (outOfDate.isEmpty()) return;
        LOG.info(
                String.format(
                        "...%d files need to be counted for references to %s",
                        outOfDate.size(), Parser.fileName(toFile)));
        outOfDate.add(toFile);
        countReferencesCache.remove(toFile);
        var batch = compiler.compileBatch(latestText(outOfDate));

        // Find all declarations in toFile
        var allEls = batch.declarations(toFile);

        // Find all references to all declarations
        var refs = batch.references(allEls);

        // Reset all counts for files we just re-compiled
        for (var fromUri : outOfDate) {
            countReferencesCache.put(fromUri, new CountReferences());
        }

        // Increment cached counts
        for (var to : refs.keySet()) {
            var toPtr = new Ptr(to);

            for (var from : refs.get(to)) {
                var fromUri = from.getCompilationUnit().getSourceFile().toUri();
                var counts = countReferencesCache.computeIfAbsent(fromUri, __ -> new CountReferences());
                var c = counts.counts.getOrDefault(toPtr, 0);
                counts.counts.put(toPtr, c + 1);
            }
        }

        // Ensure that all fromFiles are in the cache, even if they contain no references to toFile
        for (var fromUri : fromFiles) {
            countReferencesCache.computeIfAbsent(fromUri, __ -> new CountReferences());
        }
    }

    @Override
    public List<TextEdit> formatting(DocumentFormattingParams params) {
        updateActiveFile(params.textDocument.uri);

        var edits = new ArrayList<TextEdit>();
        edits.addAll(fixImports());
        edits.addAll(addOverrides());
        // TODO replace var with type name when vars are copy-pasted into fields
        return edits;
    }

    private List<TextEdit> fixImports() {
        // TODO if imports already match fixed-imports, return empty list
        // TODO preserve comments and other details of existing imports
        var imports = activeFileCache.fixImports();
        var pos = activeFileCache.sourcePositions();
        var lines = activeFileCache.root.getLineMap();
        var edits = new ArrayList<TextEdit>();
        // Delete all existing imports
        for (var i : activeFileCache.root.getImports()) {
            if (!i.isStatic()) {
                var offset = pos.getStartPosition(activeFileCache.root, i);
                var line = (int) lines.getLineNumber(offset) - 1;
                var delete = new TextEdit(new Range(new Position(line, 0), new Position(line + 1, 0)), "");
                edits.add(delete);
            }
        }
        if (imports.isEmpty()) return edits;
        // Find a place to insert the new imports
        long insertLine = -1;
        var insertText = new StringBuilder();
        // If there are imports, use the start of the first import as the insert position
        for (var i : activeFileCache.root.getImports()) {
            if (!i.isStatic() && insertLine == -1) {
                long offset = pos.getStartPosition(activeFileCache.root, i);
                insertLine = lines.getLineNumber(offset) - 1;
            }
        }
        // If there are no imports, insert after the package declaration
        if (insertLine == -1 && activeFileCache.root.getPackageName() != null) {
            long offset = pos.getEndPosition(activeFileCache.root, activeFileCache.root.getPackageName());
            insertLine = lines.getLineNumber(offset);
            insertText.append("\n");
        }
        // If there are no imports and no package, insert at the top of the file
        if (insertLine == -1) {
            insertLine = 0;
        }
        // Insert each import
        for (var i : imports) {
            insertText.append("import ").append(i).append(";\n");
        }
        var insertPosition = new Position((int) insertLine, 0);
        var insert = new TextEdit(new Range(insertPosition, insertPosition), insertText.toString());
        edits.add(insert);

        return edits;
    }

    private List<TextEdit> addOverrides() {
        var edits = new ArrayList<TextEdit>();
        var methods = activeFileCache.needsOverrideAnnotation();
        var pos = activeFileCache.sourcePositions();
        var lines = activeFileCache.root.getLineMap();
        for (var t : methods) {
            var methodStart = pos.getStartPosition(t.getCompilationUnit(), t.getLeaf());
            var insertLine = lines.getLineNumber(methodStart);
            var indent = methodStart - lines.getPosition(insertLine, 0);
            var insertText = new StringBuilder();
            for (var i = 0; i < indent; i++) insertText.append(' ');
            insertText.append("@Override");
            insertText.append('\n');
            var insertPosition = new Position((int) insertLine - 1, 0);
            var insert = new TextEdit(new Range(insertPosition, insertPosition), insertText.toString());
            edits.add(insert);
        }
        return edits;
    }

    @Override
    public List<FoldingRange> foldingRange(FoldingRangeParams params) {
        updateCachedParse(params.textDocument.uri);
        var folds = cacheParse.foldingRanges();
        var all = new ArrayList<FoldingRange>();

        // Merge import ranges
        if (!folds.imports.isEmpty()) {
            var merged = asFoldingRange(folds.imports.get(0), FoldingRangeKind.Imports);
            for (var i : folds.imports) {
                var r = asFoldingRange(i, FoldingRangeKind.Imports);
                if (r.startLine <= merged.endLine + 1) {
                    merged =
                            new FoldingRange(
                                    merged.startLine,
                                    merged.startCharacter,
                                    r.endLine,
                                    r.endCharacter,
                                    FoldingRangeKind.Imports);
                } else {
                    all.add(merged);
                    merged = r;
                }
            }
            all.add(merged);
        }

        // Convert blocks and comments
        for (var t : folds.blocks) {
            all.add(asFoldingRange(t, FoldingRangeKind.Region));
        }
        for (var t : folds.comments) {
            all.add(asFoldingRange(t, FoldingRangeKind.Region));
        }

        return all;
    }

    private FoldingRange asFoldingRange(TreePath t, String kind) {
        var pos = cacheParse.sourcePositions();
        var lines = t.getCompilationUnit().getLineMap();
        var start = (int) pos.getStartPosition(t.getCompilationUnit(), t.getLeaf());
        var end = (int) pos.getEndPosition(t.getCompilationUnit(), t.getLeaf());

        // If this is a class tree, adjust start position to '{'
        if (t.getLeaf() instanceof ClassTree) {
            CharSequence content;
            try {
                content = t.getCompilationUnit().getSourceFile().getCharContent(true);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
            for (var i = start; i < content.length(); i++) {
                if (content.charAt(i) == '{') {
                    start = i;
                    break;
                }
            }
        }

        // Convert offset to 0-based line and character
        var startLine = (int) lines.getLineNumber(start) - 1;
        var startChar = (int) lines.getColumnNumber(start) - 1;
        var endLine = (int) lines.getLineNumber(end) - 1;
        var endChar = (int) lines.getColumnNumber(end) - 1;

        // If this is a block, move end position back one line so we don't fold the '}'
        if (t.getLeaf() instanceof ClassTree || t.getLeaf() instanceof BlockTree) {
            endLine--;
        }

        return new FoldingRange(startLine, startChar, endLine, endChar, kind);
    }

    @Override
    public Optional<RenameResponse> prepareRename(TextDocumentPositionParams params) {
        throw new RuntimeException("TODO");
    }

    @Override
    public WorkspaceEdit rename(RenameParams params) {
        throw new RuntimeException("TODO");
    }

    static boolean isJavaFile(URI uri) {
        return uri.getScheme().equals("file") && uri.getPath().endsWith(".java");
    }

    private List<URI> recentlyOpened = new ArrayList<>();

    @Override
    public void didOpenTextDocument(DidOpenTextDocumentParams params) {
        var document = params.textDocument;
        var uri = document.uri;
        if (!isJavaFile(uri)) return;
        LOG.info(String.format("Opened %s", Parser.fileName(uri)));
        activeDocuments.put(uri, new VersionedContent(document.text, document.version));
        recentlyOpened.add(uri);
        updateCachedParse(uri); // So that subsequent documentSymbol and codeLens requests will be faster
    }

    @Override
    public void didChangeTextDocument(DidChangeTextDocumentParams params) {
        var document = params.textDocument;
        var uri = document.uri;
        if (isJavaFile(uri)) {
            var existing = activeDocuments.get(uri);
            var newText = existing.content;

            if (document.version > existing.version) {
                for (var change : params.contentChanges) {
                    if (change.range == null) newText = change.text;
                    else newText = patch(newText, change);
                }

                activeDocuments.put(uri, new VersionedContent(newText, document.version));
            } else LOG.warning("Ignored change with version " + document.version + " <= " + existing.version);
        }
    }

    private String patch(String sourceText, TextDocumentContentChangeEvent change) {
        try {
            var range = change.range;
            var reader = new BufferedReader(new StringReader(sourceText));
            var writer = new StringWriter();

            // Skip unchanged lines
            int line = 0;

            while (line < range.start.line) {
                writer.write(reader.readLine() + '\n');
                line++;
            }

            // Skip unchanged chars
            for (int character = 0; character < range.start.character; character++) writer.write(reader.read());

            // Write replacement text
            writer.write(change.text);

            // Skip replaced text
            reader.skip(change.rangeLength);

            // Write remaining text
            while (true) {
                int next = reader.read();

                if (next == -1) return writer.toString();
                else writer.write(next);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void didCloseTextDocument(DidCloseTextDocumentParams params) {
        var document = params.textDocument;
        var uri = document.uri;
        if (isJavaFile(uri)) {
            // Remove from source cache
            activeDocuments.remove(uri);

            // Clear diagnostics
            publishDiagnostics(Collections.singletonList(uri), List.of());
        }
    }

    @Override
    public void didSaveTextDocument(DidSaveTextDocumentParams params) {
        var uri = params.textDocument.uri;
        if (isJavaFile(uri)) {
            // Re-lint all active documents
            reportErrors(activeDocuments.keySet());
        }
    }

    Set<URI> activeDocuments() {
        return activeDocuments.keySet();
    }

    int version(URI openFile) {
        if (!activeDocuments.containsKey(openFile)) return -1;
        return activeDocuments.get(openFile).version;
    }

    String contents(URI openFile) {
        if (!isJavaFile(openFile)) {
            LOG.warning("Ignoring non-java file " + openFile);
            return "";
        }
        if (activeDocuments.containsKey(openFile)) {
            return activeDocuments.get(openFile).content;
        }
        try {
            return Files.readAllLines(Paths.get(openFile)).stream().collect(Collectors.joining("\n"));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
