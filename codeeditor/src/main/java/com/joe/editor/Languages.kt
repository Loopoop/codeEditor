package com.joe.editor

/** Built-in languages + registry. Add your own with [Languages.register]. */
object Languages {
    private fun w(s: String): Set<String> = s.trim().split(Regex("\\s+")).toSet()
    private fun sn(trigger: String, desc: String, body: String) = Snippet(trigger, desc, body)

    private const val OPS = "+-*/%=<>!&|^~?:"
    private val SLASH_COMMENTS = listOf("/*" to "*/")
    private val NO_PAIRS = emptyMap<Char, Char>()
    private val BRACES = mapOf('{' to '}', '(' to ')', '[' to ']')

    // ---------- specs ----------
    private val kotlinSpec = LanguageSpec(
        keywords = w("as break class continue do else for fun if in interface is object package return super this throw try typealias typeof val var when while by catch constructor delegate dynamic field file finally get import init param property receiver set setparam value where abstract actual annotation companion const crossinline data enum expect external final infix inline inner internal lateinit noinline open operator out override private protected public reified sealed suspend tailrec vararg"),
        types = w("Int Long Short Byte Float Double Boolean Char String Unit Nothing Any Array List Map Set MutableList MutableMap MutableSet Pair Triple Sequence Result"),
        builtins = w("println print listOf mapOf setOf mutableListOf mutableMapOf mutableSetOf arrayOf lazy require check error TODO run let apply also with repeat emptyList"),
        literals = w("true false null"),
        lineComments = listOf("//"), blockComments = SLASH_COMMENTS, nestedBlockComments = true,
        multilineQuotes = listOf("\"\"\""), annotationPrefix = '@', operators = OPS,
    )
    private val gradleBuiltins = w("plugins id apply dependencies implementation api compileOnly runtimeOnly testImplementation androidTestImplementation kapt ksp repositories mavenCentral google gradlePluginPortal maven android defaultConfig buildTypes release debug compileSdk minSdk targetSdk versionCode versionName namespace buildFeatures compose composeOptions kotlinOptions sourceSets task tasks project rootProject settings include pluginManagement dependencyResolutionManagement")

    private val javaSpec = LanguageSpec(
        keywords = w("abstract assert break case catch class const continue default do else enum extends final finally for goto if implements import instanceof interface native new package private protected public return static strictfp super switch synchronized this throw throws transient try volatile while var record sealed permits yield"),
        types = w("int long short byte float double boolean char void String Object Integer Long Double Boolean List Map Set ArrayList HashMap Optional"),
        builtins = w("System out println print"),
        literals = w("true false null"),
        lineComments = listOf("//"), blockComments = SLASH_COMMENTS,
        multilineQuotes = listOf("\"\"\""), annotationPrefix = '@', operators = OPS,
    )

    private val groovySpec = LanguageSpec(
        keywords = w("def class interface enum trait extends implements import package return if else for while do switch case default break continue try catch finally throw new in as instanceof static final abstract private protected public void this super assert"),
        types = w("String Integer Boolean File List Map Closure Object"),
        builtins = gradleBuiltins,
        literals = w("true false null"),
        lineComments = listOf("//"), blockComments = SLASH_COMMENTS,
        multilineQuotes = listOf("\"\"\"", "'''"), annotationPrefix = '@', operators = OPS,
    )

    private val jsKeywords = "async await break case catch class const continue debugger default delete do else export extends finally for function if import in instanceof let new of return static super switch this throw try typeof var void while with yield from as get set"
    private val jsSpec = LanguageSpec(
        keywords = w(jsKeywords),
        types = w("String Number Boolean Symbol BigInt Date RegExp Error Array Object Promise Map Set"),
        builtins = w("console document window Math JSON require module exports parseInt parseFloat setTimeout setInterval fetch process"),
        literals = w("true false null undefined NaN Infinity"),
        lineComments = listOf("//"), blockComments = SLASH_COMMENTS,
        multilineQuotes = listOf("`"), annotationPrefix = '@', identStartExtra = "$", identPartExtra = "$",
        regexLiterals = true, operators = OPS,
    )
    private val tsSpec = jsSpec.copy(
        keywords = jsSpec.keywords + w("interface type enum implements namespace declare abstract readonly private protected public keyof infer is satisfies unknown never any"),
        types = jsSpec.types + w("string number boolean object"),
    )

    private val phpSpec = LanguageSpec(
        keywords = w("abstract and array as break callable case catch class clone const continue declare default do echo else elseif empty enddeclare endfor endforeach endif endswitch endwhile extends final finally fn for foreach function global goto if implements include include_once instanceof insteadof interface isset list match namespace new or print private protected public readonly require require_once return static switch throw trait try unset use var while xor yield enum"),
        types = w("int float string bool array object mixed void never self parent iterable callable"),
        builtins = w("count strlen str_replace explode implode in_array array_map array_filter array_merge json_encode json_decode var_dump print_r isset unset define"),
        literals = w("true false null TRUE FALSE NULL"),
        lineComments = listOf("//", "#"), blockComments = SLASH_COMMENTS, variablePrefix = '$',
        specialTokens = listOf("<?php" to TokenType.Meta, "<?=" to TokenType.Meta, "?>" to TokenType.Meta),
        operators = OPS,
    )

    private val rustSpec = LanguageSpec(
        keywords = w("as async await break const continue crate dyn else enum extern fn for if impl in let loop match mod move mut pub ref return self Self static struct super trait type unsafe use where while union"),
        types = w("i8 i16 i32 i64 i128 isize u8 u16 u32 u64 u128 usize f32 f64 bool char str String Vec Option Result Box Rc Arc HashMap HashSet BTreeMap"),
        builtins = w("Some None Ok Err drop"),
        literals = w("true false"),
        lineComments = listOf("//"), blockComments = SLASH_COMMENTS, nestedBlockComments = true,
        lifetimes = true, macroBang = true, hashAttributes = true, operators = OPS,
    )

    private val goSpec = LanguageSpec(
        keywords = w("break case chan const continue default defer else fallthrough for func go goto if import interface map package range return select struct switch type var"),
        types = w("int int8 int16 int32 int64 uint uint8 uint16 uint32 uint64 uintptr float32 float64 complex64 complex128 bool byte rune string error any"),
        builtins = w("append cap close copy delete len make new panic print println recover"),
        literals = w("true false nil iota"),
        lineComments = listOf("//"), blockComments = SLASH_COMMENTS,
        multilineQuotes = listOf("`"), rawMultiline = true, operators = OPS,
    )

    private val cKeywords = "auto break case const continue default do else enum extern for goto if inline register restrict return sizeof static struct switch typedef union volatile while"
    private val cSpec = LanguageSpec(
        keywords = w(cKeywords),
        types = w("int long short char float double void signed unsigned size_t bool uint8_t uint16_t uint32_t uint64_t int8_t int16_t int32_t int64_t FILE _Bool"),
        builtins = w("printf scanf malloc free calloc realloc memcpy memset strlen strcpy strcmp sprintf fopen fclose puts"),
        literals = w("NULL true false"),
        lineComments = listOf("//"), blockComments = SLASH_COMMENTS, preprocessor = true, operators = OPS,
    )
    private val cppSpec = cSpec.copy(
        keywords = cSpec.keywords + w("alignas alignof and asm catch class constexpr const_cast decltype delete dynamic_cast explicit export friend mutable namespace new noexcept not operator or private protected public reinterpret_cast static_assert static_cast template this thread_local throw try typeid typename using virtual override final concept requires co_await co_return co_yield"),
        types = cSpec.types + w("string vector map set unordered_map unique_ptr shared_ptr auto wchar_t array optional"),
        builtins = cSpec.builtins + w("std cout cin endl cerr make_unique make_shared move"),
        literals = cSpec.literals + w("nullptr"),
        multilineQuotes = listOf("R\"("),
    ).copy(multilineQuotes = emptyList())

    private val csharpSpec = LanguageSpec(
        keywords = w("abstract as base break case catch checked class const continue default delegate do else enum event explicit extern finally fixed for foreach goto if implicit in interface internal is lock namespace new operator out override params private protected public readonly ref return sealed sizeof stackalloc static struct switch this throw try typeof unchecked unsafe using virtual volatile while var async await record init required get set yield partial when where"),
        types = w("int long short byte sbyte uint ulong ushort float double decimal bool char string object void dynamic List Dictionary Task"),
        builtins = w("Console WriteLine Write ReadLine"),
        literals = w("true false null"),
        lineComments = listOf("//"), blockComments = SLASH_COMMENTS,
        multilineQuotes = listOf("\"\"\""), preprocessor = true, operators = OPS,
    )

    private val pythonSpec = LanguageSpec(
        keywords = w("and as assert async await break class continue def del elif else except finally for from global if import in is lambda nonlocal not or pass raise return try while with yield match case"),
        types = w("int str float bool list dict set tuple bytes object type"),
        builtins = w("print len range open input isinstance super enumerate zip map filter sorted sum min max abs round any all"),
        literals = w("True False None self cls"),
        lineComments = listOf("#"), multilineQuotes = listOf("\"\"\"", "'''"), annotationPrefix = '@', operators = OPS,
    )

    private val swiftSpec = LanguageSpec(
        keywords = w("associatedtype class deinit enum extension fileprivate func import init inout internal let open operator private protocol public rethrows static struct subscript typealias var break case catch continue default defer do else fallthrough for guard if in repeat return throw switch where while as await async is super self Self throws try some any actor"),
        types = w("Int Double Float Bool String Character Array Dictionary Set Optional Any AnyObject Void"),
        builtins = w("print fatalError assert precondition"),
        literals = w("true false nil"),
        lineComments = listOf("//"), blockComments = SLASH_COMMENTS, nestedBlockComments = true,
        multilineQuotes = listOf("\"\"\""), annotationPrefix = '@', operators = OPS,
    )

    private val dartSpec = LanguageSpec(
        keywords = w("abstract as assert async await break case catch class const continue covariant default deferred do dynamic else enum export extends extension external factory final finally for Function get hide if implements import in interface is late library mixin new on operator part required rethrow return set show static super switch sync this throw try typedef var void while with yield"),
        types = w("int double num bool String List Map Set Future Stream Object dynamic Widget"),
        builtins = w("print runApp"),
        literals = w("true false null"),
        lineComments = listOf("//"), blockComments = SLASH_COMMENTS,
        multilineQuotes = listOf("\"\"\"", "'''"), annotationPrefix = '@', operators = OPS,
    )

    private val rubySpec = LanguageSpec(
        keywords = w("alias and begin break case class def do else elsif end ensure for if in module next not or redo rescue retry return super then undef unless until when while yield"),
        builtins = w("puts print require require_relative attr_accessor attr_reader attr_writer include extend lambda proc raise"),
        literals = w("true false nil self"),
        lineComments = listOf("#"), annotationPrefix = '@', operators = OPS,
    )

    private val luaSpec = LanguageSpec(
        keywords = w("and break do else elseif end for function goto if in local not or repeat return then until while"),
        builtins = w("print pairs ipairs require type tostring tonumber table string math os io setmetatable getmetatable pcall error"),
        literals = w("true false nil"),
        lineComments = listOf("--"), blockComments = listOf("--[[" to "]]"), typeHeuristic = false, operators = OPS,
    )

    private val shellSpec = LanguageSpec(
        keywords = w("if then else elif fi for while until do done case esac function in select time return exit break continue local export readonly declare unset shift"),
        builtins = w("echo cd ls cat grep sed awk printf read test source alias mkdir rm cp mv chmod curl git"),
        literals = w("true false"),
        lineComments = listOf("#"), variablePrefix = '$', identPartExtra = "-", typeHeuristic = false,
        operators = "+*=<>!&|~?",
    )

    private val sqlSpec = LanguageSpec(
        keywords = w("select from where insert into values update set delete create table drop alter add column primary key foreign references index view join inner left right outer full cross on group by order having limit offset union all distinct as and or not null is in like between exists case when then else end asc desc default unique check constraint begin commit rollback"),
        types = w("int integer bigint smallint varchar char text boolean date datetime timestamp float double decimal numeric blob"),
        builtins = w("count sum avg min max coalesce now upper lower length"),
        literals = w("true false null"),
        lineComments = listOf("--"), blockComments = SLASH_COMMENTS, stringQuotes = "'\"`",
        caseInsensitive = true, typeHeuristic = false, operators = OPS,
    )

    private val yamlSpec = LanguageSpec(
        literals = w("true false null yes no on off"),
        lineComments = listOf("#"), identPartExtra = "-./", stringKeys = true, keyColon = true,
        typeHeuristic = false, operators = "-|>&*!%@",
    )

    private val jsonSpec = LanguageSpec(
        literals = w("true false null"), stringQuotes = "\"", stringKeys = true, typeHeuristic = false, operators = "",
    )

    private val tomlSpec = LanguageSpec(
        literals = w("true false"), lineComments = listOf("#"), stringQuotes = "\"'",
        stringKeys = true, typeHeuristic = false, operators = "=.,+-[]{}",
    )

    private val rSpec = LanguageSpec(
        keywords = w("if else repeat while function for in next break TRUE FALSE NULL Inf NaN NA"),
        types = w("logical integer numeric complex character raw list expression environment data.frame factor matrix"),
        builtins = w("library require source print paste sprintf c seq rep length nrow ncol names summary lm plot"),
        literals = w("TRUE FALSE NULL Inf NaN NA"), lineComments = listOf("#"),
        stringQuotes = "\"'", operators = OPS,
    )

    private val juliaSpec = LanguageSpec(
        keywords = w("baremodule begin break catch const continue do else elseif end export finally for function global if import let local macro module quote return struct try using while mutable struct where primitive type abstract primitive"),
        types = w("Int Int8 Int16 Int32 Int64 UInt UInt8 UInt16 UInt32 UInt64 Float16 Float32 Float64 Bool Char String Symbol Vector Matrix Tuple NamedTuple Dict Set Nothing Missing Any"),
        builtins = w("println print length size push! pop! map filter reduce collect range zeros ones rand"),
        literals = w("true false nothing missing"), lineComments = listOf("#"),
        blockComments = listOf("#=" to "=#"), nestedBlockComments = true,
        multilineQuotes = listOf("\"\"\""), operators = OPS,
    )

    private val haskellSpec = LanguageSpec(
        keywords = w("as case class data default deriving do else hiding if import in infix infixl infixr instance let module newtype of qualified then type where foreign forall mdo family role stock standalone via"),
        types = w("Int Integer Float Double Bool Char String Maybe Either IO Ordering Eq Ord Show Read Num Integral Real Fractional"),
        builtins = w("map filter foldr foldl print putStrLn length head tail null concat zip curry uncurry"),
        literals = w("True False Nothing Just Left Right"), lineComments = listOf("--"),
        blockComments = listOf("{-" to "-}"), nestedBlockComments = true,
        stringQuotes = "\"'", operators = OPS,
    )

    private val scalaSpec = LanguageSpec(
        keywords = w("abstract case catch class def do else extends final finally for forSome if implicit import lazy match new null object override package private protected return sealed super this throw trait try type val var while with yield given using enum opaque extension inline infix open transparent"),
        types = w("Int Long Short Byte Float Double Boolean Char String Unit Any AnyVal AnyRef Nothing Null Option List Seq Map Set Future"),
        builtins = w("println print require assert Some None Left Right"), literals = w("true false null"),
        lineComments = listOf("//"), blockComments = SLASH_COMMENTS, nestedBlockComments = true,
        multilineQuotes = listOf("\"\"\""), annotationPrefix = '@', operators = OPS,
    )

    private val elixirSpec = LanguageSpec(
        keywords = w("after alias and case catch cond def defdelegate defexception defimpl defmacro defmodule defp defprotocol defstruct defguard defguardp do else end fn for if import in not or quote raise receive require rescue try unless use when with xor") ,
        types = w("Atom BitString Binary Integer Float List Map Tuple PID Port Reference Function"),
        builtins = w("IO Enum Stream Map Keyword String Agent GenServer Task Supervisor"),
        literals = w("true false nil"), lineComments = listOf("#"),
        stringQuotes = "\"'", multilineQuotes = listOf("\"\"\""), operators = OPS,
    )

    private val erlangSpec = LanguageSpec(
        keywords = w("after begin case cond end fun if let of catch receive try when maybe else"),
        builtins = w("module export import spawn self receive io lists maps proplists gen_server supervisor"),
        literals = w("true false undefined"), lineComments = listOf("%"),
        stringQuotes = "\"'", operators = OPS,
    )

    private val powershellSpec = LanguageSpec(
        keywords = w("begin break catch class continue data define do dynamicparam else elseif end enum exit filter finally for foreach from function if in param process return static switch throw trap try until using var while workflow"),
        types = w("bool byte char datetime decimal double int int16 int32 int64 long object pscustomobject regex scriptblock single string uint uint16 uint32 uint64 ulong"),
        builtins = w("Write-Host Write-Output Write-Error Get-Item Get-ChildItem Set-Location Get-Content Set-Content New-Item Remove-Item Where-Object ForEach-Object Select-Object"),
        literals = w("true false null"), lineComments = listOf("#"), blockComments = listOf("<#" to "#>"),
        stringQuotes = "\"'", variablePrefix = '$', operators = OPS,
    )

    private val perlSpec = LanguageSpec(
        keywords = w("if elsif else unless while until for foreach continue do sub my our local state use package require return die eval given when default next last redo print say"),
        types = w("scalar array hash filehandle"), builtins = w("map grep split join sort push pop shift unshift keys values exists defined length chomp"),
        literals = w("true false undef"), lineComments = listOf("#"), variablePrefix = '$',
        stringQuotes = "\"'`", operators = OPS,
    )

    private val nixSpec = LanguageSpec(
        keywords = w("assert builtins else if in inherit let or rec then with"),
        builtins = w("abort baseNameOf concatLists elemAt fetchGit fetchurl filter hasAttr import isAttrs isList isString mapAttrs mkDerivation nixpkgs pkgs throw toString"),
        literals = w("true false null"), lineComments = listOf("#"), blockComments = listOf("/*" to "*/"),
        stringQuotes = "\"'", operators = OPS,
    )

    // ---------- languages ----------
    val PlainText = Language("plaintext", "Plain Text", listOf("txt", "text", "log"), checkBrackets = false)

    val Kotlin = codeLanguage("kotlin", "Kotlin", listOf("kt", "kts"), kotlinSpec, snippets = listOf(
        sn("fun", "function", "fun $0() {\n\t\n}"),
        sn("main", "main function", "fun main() {\n\t$0\n}"),
        sn("class", "class", "class $0 {\n\t\n}"),
        sn("dataclass", "data class", "data class $0()"),
        sn("for", "for loop", "for (i in 0 until $0) {\n\t\n}"),
        sn("if", "if", "if ($0) {\n\t\n}"),
        sn("when", "when", "when ($0) {\n\t else -> {}\n}"),
        sn("composable", "@Composable function", "@Composable\nfun $0() {\n\t\n}"),
    ))
    val GradleKts = codeLanguage("gradle-kts", "Gradle (Kotlin DSL)", listOf("gradle.kts"),
        kotlinSpec.copy(builtins = kotlinSpec.builtins + gradleBuiltins), snippets = listOf(
            sn("dep", "dependency", "implementation(\"$0\")"),
            sn("plugins", "plugins block", "plugins {\n\tid(\"$0\")\n}"),
        ))
    val Groovy = codeLanguage("gradle", "Gradle / Groovy", listOf("gradle", "groovy"), groovySpec, snippets = listOf(
        sn("dep", "dependency", "implementation '$0'"),
        sn("plugins", "plugins block", "plugins {\n\tid '$0'\n}"),
    ))
    val Java = codeLanguage("java", "Java", listOf("java"), javaSpec, snippets = listOf(
        sn("psvm", "main method", "public static void main(String[] args) {\n\t$0\n}"),
        sn("sout", "System.out.println", "System.out.println($0);"),
        sn("class", "class", "public class $0 {\n\t\n}"),
        sn("for", "for loop", "for (int i = 0; i < $0; i++) {\n\t\n}"),
        sn("if", "if", "if ($0) {\n\t\n}"),
    ))
    val JavaScript = codeLanguage("javascript", "JavaScript", listOf("js", "mjs", "cjs", "jsx"), jsSpec,
        indentUnit = "  ", snippets = listOf(
            sn("log", "console.log", "console.log($0);"),
            sn("fn", "function", "function $0() {\n\t\n}"),
            sn("af", "arrow function", "const $0 = () => {\n\t\n};"),
            sn("for", "for loop", "for (let i = 0; i < $0; i++) {\n\t\n}"),
            sn("try", "try/catch", "try {\n\t$0\n} catch (e) {\n\t\n}"),
        ))
    val TypeScript = codeLanguage("typescript", "TypeScript", listOf("ts", "tsx"), tsSpec,
        indentUnit = "  ", snippets = JavaScript.snippets + sn("interface", "interface", "interface $0 {\n\t\n}"))
    val Json = codeLanguage("json", "JSON", listOf("json", "geojson", "webmanifest"), jsonSpec,
        indentUnit = "  ", lineComment = null, blockComment = null, quotePairs = "\"")
    val Toml = codeLanguage("toml", "TOML", listOf("toml"), tomlSpec,
        indentUnit = "  ", lineComment = "#", blockComment = null, quotePairs = "\"'")
    val Html = Language(
        "html", "HTML", listOf("html", "htm", "xhtml", "vue", "svelte"),
        blockComment = "<!--" to "-->", indentUnit = "  ", indentAfter = emptySet(), pairs = NO_PAIRS,
        wordChars = "_-:", isMarkup = true,
        vocabulary = w("html head body title meta link script style div span p a img ul ol li table thead tbody tr td th form input button select option textarea label h1 h2 h3 h4 h5 h6 header footer nav main section article aside canvas video audio iframe br hr pre code strong em small").map { it to TokenType.Tag } +
            w("class id href src alt type name value placeholder style onclick rel charset content width height target action method disabled checked required").map { it to TokenType.Attr },
        snippets = listOf(
            sn("html5", "HTML5 boilerplate", "<!DOCTYPE html>\n<html lang=\"en\">\n<head>\n\t<meta charset=\"UTF-8\">\n\t<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n\t<title>$0</title>\n</head>\n<body>\n\t\n</body>\n</html>"),
            sn("div", "div", "<div class=\"$0\"></div>"),
            sn("a", "anchor", "<a href=\"$0\"></a>"),
            sn("script", "script", "<script>\n\t$0\n</script>"),
        ),
        scanner = MarkupScanner(true) { name -> if (name == "script") JavaScript else Css }::scan,
    )
    val Xml = Language(
        "xml", "XML", listOf("xml", "svg", "xsd", "xsl", "plist", "iml"),
        blockComment = "<!--" to "-->", indentUnit = "  ", indentAfter = emptySet(), pairs = NO_PAIRS,
        wordChars = "_-:.", isMarkup = true, scanner = MarkupScanner(false) { null }::scan,
    )
    val Css: Language = Language(
        "css", "CSS / SCSS", listOf("css", "scss", "less"),
        blockComment = "/*" to "*/", indentUnit = "  ", indentAfter = setOf('{', '(', '['), pairs = BRACES,
        wordChars = "_-",
        vocabulary = w("color background background-color margin padding border border-radius display flex grid position width height max-width min-height font-size font-family font-weight text-align line-height overflow opacity transition transform animation z-index cursor box-shadow justify-content align-items flex-direction gap content top left right bottom").map { it to TokenType.Property } +
            w("none block inline inline-block flex grid absolute relative fixed sticky center auto inherit solid hidden").map { it to TokenType.Literal },
        snippets = listOf(
            sn("flex", "flex center", "display: flex;\njustify-content: center;\nalign-items: center;"),
            sn("grid", "grid", "display: grid;\ngrid-template-columns: repeat($0, 1fr);\ngap: 8px;"),
            sn("media", "media query", "@media (max-width: 600px) {\n\t$0\n}"),
        ),
        scanner = CssScanner::scan,
    )
    val Php = codeLanguage("php", "PHP", listOf("php", "phtml"), phpSpec, indentUnit = "    ", snippets = listOf(
        sn("php", "php tag", "<?php\n\n$0"),
        sn("fn", "function", "function $0() {\n\t\n}"),
        sn("class", "class", "class $0 {\n\t\n}"),
        sn("foreach", "foreach", "foreach ($0 as \$item) {\n\t\n}"),
    ))
    val Rust = codeLanguage("rust", "Rust", listOf("rs"), rustSpec, quotePairs = "\"", snippets = listOf(
        sn("fn", "function", "fn $0() {\n\t\n}"),
        sn("main", "main", "fn main() {\n\t$0\n}"),
        sn("struct", "struct", "struct $0 {\n\t\n}"),
        sn("impl", "impl", "impl $0 {\n\t\n}"),
        sn("match", "match", "match $0 {\n\t_ => {}\n}"),
        sn("for", "for loop", "for i in 0..$0 {\n\t\n}"),
        sn("test", "test fn", "#[test]\nfn $0() {\n\t\n}"),
    ))
    val Go = codeLanguage("go", "Go", listOf("go"), goSpec, indentUnit = "\t", snippets = listOf(
        sn("main", "main", "func main() {\n\t$0\n}"),
        sn("func", "function", "func $0() {\n\t\n}"),
        sn("for", "for loop", "for i := 0; i < $0; i++ {\n\t\n}"),
        sn("iferr", "if err != nil", "if err != nil {\n\t$0\n}"),
        sn("struct", "struct", "type $0 struct {\n\t\n}"),
    ))
    val C = codeLanguage("c", "C", listOf("c", "h"), cSpec, snippets = listOf(
        sn("main", "main", "#include <stdio.h>\n\nint main(void) {\n\t$0\n\treturn 0;\n}"),
        sn("for", "for loop", "for (int i = 0; i < $0; i++) {\n\t\n}"),
        sn("inc", "include", "#include <$0>"),
    ))
    val Cpp = codeLanguage("cpp", "C++", listOf("cpp", "cc", "cxx", "hpp", "hh", "hxx"), cppSpec, snippets = listOf(
        sn("main", "main", "#include <iostream>\n\nint main() {\n\t$0\n\treturn 0;\n}"),
        sn("for", "for loop", "for (int i = 0; i < $0; ++i) {\n\t\n}"),
        sn("class", "class", "class $0 {\npublic:\n\t\n};"),
        sn("inc", "include", "#include <$0>"),
    ))
    val CSharp = codeLanguage("csharp", "C#", listOf("cs"), csharpSpec, snippets = listOf(
        sn("class", "class", "public class $0\n{\n\t\n}"),
        sn("cw", "Console.WriteLine", "Console.WriteLine($0);"),
    ))
    val Python = codeLanguage("python", "Python", listOf("py", "pyw"), pythonSpec,
        indentAfter = setOf(':', '(', '[', '{'), snippets = listOf(
            sn("def", "function", "def $0():\n\t"),
            sn("class", "class", "class $0:\n\tdef __init__(self):\n\t\t"),
            sn("ifmain", "main guard", "if __name__ == \"__main__\":\n\t$0"),
            sn("for", "for loop", "for i in range($0):\n\t"),
        ))
    val Swift = codeLanguage("swift", "Swift", listOf("swift"), swiftSpec)
    val Dart = codeLanguage("dart", "Dart", listOf("dart"), dartSpec, indentUnit = "  ")
    val Ruby = codeLanguage("ruby", "Ruby", listOf("rb", "rake", "gemspec"), rubySpec, indentUnit = "  ")
    val Lua = codeLanguage("lua", "Lua", listOf("lua"), luaSpec, indentUnit = "  ")
    val Shell = codeLanguage("shell", "Shell", listOf("sh", "bash", "zsh"), shellSpec, indentUnit = "  ", checkBrackets = false)
    val Sql = codeLanguage("sql", "SQL", listOf("sql"), sqlSpec, indentUnit = "  ")
    val Yaml = codeLanguage("yaml", "YAML", listOf("yml", "yaml"), yamlSpec, indentUnit = "  ",
        indentAfter = setOf(':', '[', '{'), checkBrackets = false)
    val Markdown = Language(
        "markdown", "Markdown", listOf("md", "markdown"), blockComment = "<!--" to "-->",
        indentUnit = "  ", indentAfter = emptySet(), checkBrackets = false, scanner = MarkdownScanner::scan,
    )
    val R = codeLanguage("r", "R", listOf("r", "rdata", "rds"), rSpec, indentUnit = "  ")
    val Julia = codeLanguage("julia", "Julia", listOf("jl"), juliaSpec, indentUnit = "  ")
    val Haskell = codeLanguage("haskell", "Haskell", listOf("hs", "lhs"), haskellSpec, indentUnit = "  ")
    val Scala = codeLanguage("scala", "Scala", listOf("scala", "sc"), scalaSpec, indentUnit = "  ")
    val Elixir = codeLanguage("elixir", "Elixir", listOf("ex", "exs"), elixirSpec, indentUnit = "  ")
    val Erlang = codeLanguage("erlang", "Erlang", listOf("erl", "hrl"), erlangSpec, indentUnit = "  ")
    val PowerShell = codeLanguage("powershell", "PowerShell", listOf("ps1", "psm1", "psd1"), powershellSpec, indentUnit = "  ")
    val Perl = codeLanguage("perl", "Perl", listOf("pl", "pm", "t"), perlSpec, indentUnit = "  ")
    val Nix = codeLanguage("nix", "Nix", listOf("nix"), nixSpec, indentUnit = "  ")

    private val registry = mutableListOf(
        PlainText, Kotlin, GradleKts, Groovy, Java, JavaScript, TypeScript, Json, Toml, Html, Xml, Css, Php, Rust, Go,
        C, Cpp, CSharp, Python, Swift, Dart, Ruby, Lua, Shell, Sql, Yaml, Markdown, R, Julia, Haskell, Scala,
        Elixir, Erlang, PowerShell, Perl, Nix,
    )

    val all: List<Language> get() = registry

    fun register(language: Language) {
        registry.removeAll { it.id == language.id }
        registry.add(language)
    }

    fun byId(id: String): Language? = registry.firstOrNull { it.id == id }

    fun forExtension(ext: String): Language? {
        val e = ext.lowercase().removePrefix(".")
        return registry.firstOrNull { l -> l.extensions.any { it == e } }
    }

    fun forFileName(fileName: String): Language {
        val n = fileName.substringAfterLast('/').substringAfterLast('\\').lowercase()
        if (n.endsWith(".gradle.kts")) return GradleKts
        if (n == "dockerfile" || n.startsWith("dockerfile.")) return Shell
        if (n == "makefile" || n == "gnumakefile") return Shell
        return forExtension(n.substringAfterLast('.', "")) ?: PlainText
    }
}
