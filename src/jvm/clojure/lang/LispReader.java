/**
 *   Copyright (c) Rich Hickey. All rights reserved.
 *   The use and distribution terms for this software are covered by the
 *   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
 *   which can be found in the file epl-v10.html at the root of this distribution.
 *   By using this software in any fashion, you are agreeing to be bound by
 * 	 the terms of this license.
 *   You must not remove this notice, or any other, from this software.
 **/

package clojure.lang;

import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;
import java.lang.Character;
import java.lang.Class;
import java.lang.Exception;
import java.lang.IllegalArgumentException;
import java.lang.IllegalStateException;
import java.lang.Integer;
import java.lang.Number;
import java.lang.NumberFormatException;
import java.lang.Object;
import java.lang.RuntimeException;
import java.lang.String;
import java.lang.StringBuilder;
import java.lang.Throwable;
import java.lang.UnsupportedOperationException;
import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LispReader{

static final Symbol QUOTE = Symbol.intern("quote");//引述
static final Symbol THE_VAR = Symbol.intern("var");
//static Symbol SYNTAX_QUOTE = Symbol.intern(null, "syntax-quote");//语法引述
static Symbol UNQUOTE = Symbol.intern("clojure.core", "unquote");//取消语法引述
static Symbol UNQUOTE_SPLICING = Symbol.intern("clojure.core", "unquote-splicing");//取消语法引述并拼接
static Symbol CONCAT = Symbol.intern("clojure.core", "concat");
static Symbol SEQ = Symbol.intern("clojure.core", "seq");
static Symbol LIST = Symbol.intern("clojure.core", "list");
static Symbol APPLY = Symbol.intern("clojure.core", "apply");
static Symbol HASHMAP = Symbol.intern("clojure.core", "hash-map");
static Symbol HASHSET = Symbol.intern("clojure.core", "hash-set");
static Symbol VECTOR = Symbol.intern("clojure.core", "vector");
static Symbol WITH_META = Symbol.intern("clojure.core", "with-meta");
static Symbol META = Symbol.intern("clojure.core", "meta");
static Symbol DEREF = Symbol.intern("clojure.core", "deref");
static Keyword UNKNOWN = Keyword.intern(null, "unknown");
//static Symbol DEREF_BANG = Symbol.intern("clojure.core", "deref!");

static IFn[] macros = new IFn[256];
static IFn[] dispatchMacros = new IFn[256];
//static Pattern symbolPat = Pattern.compile("[:]?([\\D&&[^:/]][^:/]*/)?[\\D&&[^:/]][^:/]*");
static Pattern symbolPat = Pattern.compile("[:]?([\\D&&[^/]].*/)?(/|[\\D&&[^/]][^/]*)");
//static Pattern varPat = Pattern.compile("([\\D&&[^:\\.]][^:\\.]*):([\\D&&[^:\\.]][^:\\.]*)");
//static Pattern intPat = Pattern.compile("[-+]?[0-9]+\\.?");
static Pattern intPat =
		Pattern.compile(
				"([-+]?)(?:(0)|([1-9][0-9]*)|0[xX]([0-9A-Fa-f]+)|0([0-7]+)|([1-9][0-9]?)[rR]([0-9A-Za-z]+)|0[0-9]+)(N)?");
static Pattern ratioPat = Pattern.compile("([-+]?[0-9]+)/([0-9]+)");
static Pattern floatPat = Pattern.compile("([-+]?[0-9]+(\\.[0-9]*)?([eE][-+]?[0-9]+)?)(M)?");
//static Pattern accessorPat = Pattern.compile("\\.[a-zA-Z_]\\w*");
//static Pattern instanceMemberPat = Pattern.compile("\\.([a-zA-Z_][\\w\\.]*)\\.([a-zA-Z_]\\w*)");
//static Pattern staticMemberPat = Pattern.compile("([a-zA-Z_][\\w\\.]*)\\.([a-zA-Z_]\\w*)");
//static Pattern classNamePat = Pattern.compile("([a-zA-Z_][\\w\\.]*)\\.");

//symbol->gensymbol
static Var GENSYM_ENV = Var.create(null).setDynamic();
//sorted-map num->gensymbol
static Var ARG_ENV = Var.create(null).setDynamic();
static IFn ctorReader = new CtorReader();//记录字面量，或者reader tag
//读取器宏
static
	{
	macros['"'] = new StringReader();
	macros[';'] = new CommentReader();
	macros['\''] = new WrappingReader(QUOTE);//将下一个形式，包裹到quote中
	macros['@'] = new WrappingReader(DEREF);//new DerefReader();
	macros['^'] = new MetaReader();//元数据读取器
	macros['`'] = new SyntaxQuoteReader();//语法引述
	macros['~'] = new UnquoteReader();//取消语法引述（包括取消语法引述并拼接）
	macros['('] = new ListReader();//列表读取器
	macros[')'] = new UnmatchedDelimiterReader();
	macros['['] = new VectorReader();
	macros[']'] = new UnmatchedDelimiterReader();
	macros['{'] = new MapReader();
	macros['}'] = new UnmatchedDelimiterReader();
//	macros['|'] = new ArgVectorReader();
	macros['\\'] = new CharacterReader();
	macros['%'] = new ArgReader();//函数字面量中的参数
	macros['#'] = new DispatchReader();

	//跟在井号之后的读取器宏
	dispatchMacros['^'] = new MetaReader();//带元数据的形式
	dispatchMacros['\''] = new VarReader();//#'a，即(var a)
	dispatchMacros['"'] = new RegexReader();//正则表达式
	dispatchMacros['('] = new FnReader();//函数字面量
	dispatchMacros['{'] = new SetReader();//集字母量
	dispatchMacros['='] = new EvalReader();//#= 读取期求值？
	dispatchMacros['!'] = new CommentReader();//注释 #! ，和 分号相同，一直到行尾
	dispatchMacros['<'] = new UnreadableReader();//不可达？ #< ，直接抛出异常
	dispatchMacros['_'] = new DiscardReader();//丢弃下一个形式 #_
	}

static boolean isWhitespace(int ch){
	return Character.isWhitespace(ch) || ch == ',';
}
//回吐一个字符
static void unread(PushbackReader r, int ch) {
	if(ch != -1)
		try
			{
			r.unread(ch);
			}
		catch(IOException e)
			{
			throw Util.sneakyThrow(e);
			}
}

public static class ReaderException extends RuntimeException{
	final int line;
	final int column;

	public ReaderException(int line, int column, Throwable cause){
		super(cause);
		this.line = line;
		this.column = column;
	}
}
//读取一个字符
static public int read1(Reader r){
	try
		{
		return r.read();
		}
	catch(IOException e)
		{
		throw Util.sneakyThrow(e);
		}
}
//@param eofIsError 到达文件末是否是一个错误
//@param eofValue 如果到达文件末不是一个错误，返回eofValue
//@param isRecursive ？
static public Object read(PushbackReader r, boolean eofIsError, Object eofValue, boolean isRecursive)
{
	if(RT.READEVAL.deref() == UNKNOWN)
		throw Util.runtimeException("Reading disallowed - *read-eval* bound to :unknown");

	try
		{
		for(; ;)
			{
			int ch = read1(r);

			while(isWhitespace(ch))//忽略空白符，包括逗号
				ch = read1(r);

			if(ch == -1)//文件末尾
				{
				if(eofIsError)
					throw Util.runtimeException("EOF while reading");
				return eofValue;
				}

			if(Character.isDigit(ch))//不带正负号的数值
				{
				Object n = readNumber(r, (char) ch);
				if(RT.suppressRead())
					return null;
				return n;
				}

			IFn macroFn = getMacro(ch);
			if(macroFn != null)//读取宏字符
				{
				Object ret = macroFn.invoke(r, (char) ch);
				if(RT.suppressRead())
					return null;
				//no op macros return the reader
				if(ret == r)
					continue;
				return ret;
				}

			if(ch == '+' || ch == '-')
				{
				int ch2 = read1(r);
				if(Character.isDigit(ch2))//带正负号的数值
					{
					unread(r, ch2);
					Object n = readNumber(r, (char) ch);
					if(RT.suppressRead())
						return null;
					return n;
					}
				unread(r, ch2);//如果不是作为数值前面的正负号，回吐
				}

			String token = readToken(r, (char) ch);//其它tokon，包括nil，true，false，Symbol（Var，关键字，命名空间）
			if(RT.suppressRead())
				return null;
			return interpretToken(token);//识别token
			}
		}
	catch(Exception e)
		{
		if(isRecursive || !(r instanceof LineNumberingPushbackReader))
			throw Util.sneakyThrow(e);
		LineNumberingPushbackReader rdr = (LineNumberingPushbackReader) r;
		//throw Util.runtimeException(String.format("ReaderError:(%d,1) %s", rdr.getLineNumber(), e.getMessage()), e);
		throw new ReaderException(rdr.getLineNumber(), rdr.getColumnNumber(), e);
		}
}

static private String readToken(PushbackReader r, char initch) {
	StringBuilder sb = new StringBuilder();
	sb.append(initch);

	for(; ;)
		{
		int ch = read1(r);
		if(ch == -1 || isWhitespace(ch) || isTerminatingMacro(ch))
			{
			unread(r, ch);
			return sb.toString();
			}
		sb.append((char) ch);
		}
}
//不仅仅是空白符，遇到读取宏字符也会停止
static private Object readNumber(PushbackReader r, char initch) {
	StringBuilder sb = new StringBuilder();
	sb.append(initch);

	for(; ;)
		{
		int ch = read1(r);
		if(ch == -1 || isWhitespace(ch) || isMacro(ch))
			{
			unread(r, ch);
			break;
			}
		sb.append((char) ch);
		}

	String s = sb.toString();
	Object n = matchNumber(s);
	if(n == null)
		throw new NumberFormatException("Invalid number: " + s);
	return n;
}

static private int readUnicodeChar(String token, int offset, int length, int base) {
	if(token.length() != offset + length)
		throw new IllegalArgumentException("Invalid unicode character: \\" + token);
	int uc = 0;
	for(int i = offset; i < offset + length; ++i)
		{
		int d = Character.digit(token.charAt(i), base);
		if(d == -1)
			throw new IllegalArgumentException("Invalid digit: " + token.charAt(i));
		uc = uc * base + d;
		}
	return (char) uc;
}

static private int readUnicodeChar(PushbackReader r, int initch, int base, int length, boolean exact) {
	int uc = Character.digit(initch, base);
	if(uc == -1)
		throw new IllegalArgumentException("Invalid digit: " + (char) initch);
	int i = 1;
	for(; i < length; ++i)
		{
		int ch = read1(r);
		if(ch == -1 || isWhitespace(ch) || isMacro(ch))
			{
			unread(r, ch);
			break;
			}
		int d = Character.digit(ch, base);
		if(d == -1)
			throw new IllegalArgumentException("Invalid digit: " + (char) ch);
		uc = uc * base + d;
		}
	if(i != length && exact)
		throw new IllegalArgumentException("Invalid character length: " + i + ", should be: " + length);
	return uc;
}
//识别token
static private Object interpretToken(String s) {
	if(s.equals("nil"))
		{
		return null;
		}
	else if(s.equals("true"))
		{
		return RT.T;
		}
	else if(s.equals("false"))
		{
		return RT.F;
		}
	Object ret = null;

	ret = matchSymbol(s);
	if(ret != null)
		return ret;

	throw Util.runtimeException("Invalid token: " + s);
}

//返回null，关键字，或符号
private static Object matchSymbol(String s){
	Matcher m = symbolPat.matcher(s);
	if(m.matches())
		{
		int gc = m.groupCount();
		String ns = m.group(1);
		String name = m.group(2);
		if(ns != null && ns.endsWith(":/")//命名空间以:/结尾，违法
		   || name.endsWith(":")//名字以冒号结尾，违法
		   || s.indexOf("::", 1) != -1)//三冒号开头，或开头之外还有双冒号，违法
			return null;
		//hxzon：(= :x ::x) false，一个无命名空间，一个当前命名空间
		// (= :clojure.core/y ::clojure.core/y) true
		if(s.startsWith("::"))
			{//以两个冒号开头，不含斜杠，表示是当前命名空间的关键字，含斜杠，表示特定命名空间的关键字
			Symbol ks = Symbol.intern(s.substring(2));
			Namespace kns;
			if(ks.ns != null)//::x/y时，ks.ns=x
				kns = Compiler.namespaceFor(ks);
			else
				kns = Compiler.currentNS();
			//auto-resolving keyword
			if (kns != null)
				return Keyword.intern(kns.name.name,ks.name);//当命名空间存在时，例如::clojure.core/y
			else
				return null;//当命名空间x不存在时，返回null（双冒号且带有命名空间限定时，要求命名空间已存在）
			}
		boolean isKeyword = s.charAt(0) == ':';//以单个冒号开头的为关键字
		Symbol sym = Symbol.intern(s.substring(isKeyword ? 1 : 0));//一个新的Symbol对象,new Symbol(xxx)
		if(isKeyword)
			return Keyword.intern(sym);//查找或创建关键字
		return sym;//返回符号
		}
	return null;
}


private static Object matchNumber(String s){
	Matcher m = intPat.matcher(s);
	if(m.matches())
		{
		if(m.group(2) != null)
			{
			if(m.group(8) != null)
				return BigInt.ZERO;
			return Numbers.num(0);
			}
		boolean negate = (m.group(1).equals("-"));
		String n;
		int radix = 10;
		if((n = m.group(3)) != null)
			radix = 10;
		else if((n = m.group(4)) != null)
			radix = 16;
		else if((n = m.group(5)) != null)
			radix = 8;
		else if((n = m.group(7)) != null)
			radix = Integer.parseInt(m.group(6));
		if(n == null)
			return null;
		BigInteger bn = new BigInteger(n, radix);
		if(negate)
			bn = bn.negate();
		if(m.group(8) != null)
			return BigInt.fromBigInteger(bn);
		return bn.bitLength() < 64 ?
		       Numbers.num(bn.longValue())
		                           : BigInt.fromBigInteger(bn);
		}
	m = floatPat.matcher(s);
	if(m.matches())
		{
		if(m.group(4) != null)
			return new BigDecimal(m.group(1));
		return Double.parseDouble(s);
		}
	m = ratioPat.matcher(s);
	if(m.matches())
		{
		String numerator = m.group(1);
		if (numerator.startsWith("+")) numerator = numerator.substring(1);

		return Numbers.divide(Numbers.reduceBigInt(BigInt.fromBigInteger(new BigInteger(numerator))),
		                      Numbers.reduceBigInt(BigInt.fromBigInteger(new BigInteger(m.group(2)))));
		}
	return null;
}

static private IFn getMacro(int ch){
	if(ch < macros.length)
		return macros[ch];
	return null;
}

static private boolean isMacro(int ch){
	return (ch < macros.length && macros[ch] != null);
}
//视为token终止的字符（除了井号，单引号，百分号之外的读取器宏字符）
static private boolean isTerminatingMacro(int ch){
	return (ch != '#' && ch != '\'' && ch != '%' && isMacro(ch));
}
//=====================================
//正则表达式
public static class RegexReader extends AFn{
	static StringReader stringrdr = new StringReader();

	public Object invoke(Object reader, Object doublequote) {
		StringBuilder sb = new StringBuilder();
		Reader r = (Reader) reader;
		for(int ch = read1(r); ch != '"'; ch = read1(r))
			{
			if(ch == -1)
				throw Util.runtimeException("EOF while reading regex");
			sb.append( (char) ch );
			if(ch == '\\')	//escape
				{
				ch = read1(r);
				if(ch == -1)
					throw Util.runtimeException("EOF while reading regex");
				sb.append( (char) ch ) ;
				}
			}
		return Pattern.compile(sb.toString());
	}
}

public static class StringReader extends AFn{
	public Object invoke(Object reader, Object doublequote) {
		StringBuilder sb = new StringBuilder();
		Reader r = (Reader) reader;

		for(int ch = read1(r); ch != '"'; ch = read1(r))
			{
			if(ch == -1)
				throw Util.runtimeException("EOF while reading string");
			if(ch == '\\')	//escape
				{
				ch = read1(r);
				if(ch == -1)
					throw Util.runtimeException("EOF while reading string");
				switch(ch)
					{
					case 't':
						ch = '\t';
						break;
					case 'r':
						ch = '\r';
						break;
					case 'n':
						ch = '\n';
						break;
					case '\\':
						break;
					case '"':
						break;
					case 'b':
						ch = '\b';
						break;
					case 'f':
						ch = '\f';
						break;
					case 'u':
					{
					ch = read1(r);
					if (Character.digit(ch, 16) == -1)
						throw Util.runtimeException("Invalid unicode escape: \\u" + (char) ch);
					ch = readUnicodeChar((PushbackReader) r, ch, 16, 4, true);
					break;
					}
					default:
					{
					if(Character.isDigit(ch))
						{
						ch = readUnicodeChar((PushbackReader) r, ch, 8, 3, false);
						if(ch > 0377)
							throw Util.runtimeException("Octal escape sequence must be in range [0, 377].");
						}
					else
						throw Util.runtimeException("Unsupported escape character: \\" + (char) ch);
					}
					}
				}
			sb.append((char) ch);
			}
		return sb.toString();
	}
}
//注释，分号，或者 #! ，一直读到行尾
public static class CommentReader extends AFn{
	public Object invoke(Object reader, Object semicolon) {
		Reader r = (Reader) reader;
		int ch;
		do
			{
			ch = read1(r);
			} while(ch != -1 && ch != '\n' && ch != '\r');
		return r;
	}

}
//丢弃： #_
public static class DiscardReader extends AFn{
	public Object invoke(Object reader, Object underscore) {
		PushbackReader r = (PushbackReader) reader;
		read(r, true, null, true);
		return r;
	}
}
//包裹，例如 @ 和 ' 等
public static class WrappingReader extends AFn{
	final Symbol sym;

	public WrappingReader(Symbol sym){
		this.sym = sym;
	}

	public Object invoke(Object reader, Object quote) {
		PushbackReader r = (PushbackReader) reader;
		Object o = read(r, true, null, true);
		return RT.list(sym, o);
	}

}
//已不再使用的过时的包裹（目前未使用）
public static class DeprecatedWrappingReader extends AFn{
	final Symbol sym;
	final String macro;

	public DeprecatedWrappingReader(Symbol sym, String macro){
		this.sym = sym;
		this.macro = macro;
	}

	public Object invoke(Object reader, Object quote) {
		System.out.println("WARNING: reader macro " + macro +
		                   " is deprecated; use " + sym.getName() +
		                   " instead");
		PushbackReader r = (PushbackReader) reader;
		Object o = read(r, true, null, true);
		return RT.list(sym, o);
	}

}
// #'a 转成 (var a)
public static class VarReader extends AFn{
	public Object invoke(Object reader, Object quote) {
		PushbackReader r = (PushbackReader) reader;
		Object o = read(r, true, null, true);
//		if(o instanceof Symbol)
//			{
//			Object v = Compiler.maybeResolveIn(Compiler.currentNS(), (Symbol) o);
//			if(v instanceof Var)
//				return v;
//			}
		return RT.list(THE_VAR, o);
	}
}

/*
static class DerefReader extends AFn{

	public Object invoke(Object reader, Object quote) {
		PushbackReader r = (PushbackReader) reader;
		int ch = read1(r);
		if(ch == -1)
			throw Util.runtimeException("EOF while reading character");
		if(ch == '!')
			{
			Object o = read(r, true, null, true);
			return RT.list(DEREF_BANG, o);
			}
		else
			{
			r.unread(ch);
			Object o = read(r, true, null, true);
			return RT.list(DEREF, o);
			}
	}

}
*/
//井号分派，如果不是“能跟在井号后”的读取宏字符，则尝试记录字面量，或者reader tag
public static class DispatchReader extends AFn{
	public Object invoke(Object reader, Object hash) {
		int ch = read1((Reader) reader);
		if(ch == -1)
			throw Util.runtimeException("EOF while reading character");
		IFn fn = dispatchMacros[ch];

		// Try the ctor reader first
		if(fn == null) {
		unread((PushbackReader) reader, ch);
		Object result = ctorReader.invoke(reader, ch);//尝试记录字面量，或者reader tag

		if(result != null)
			return result;
		else
			throw Util.runtimeException(String.format("No dispatch macro for: %c", (char) ch));
		}
		return fn.invoke(reader, ch);
	}
}

static Symbol garg(int n){
	return Symbol.intern(null, (n == -1 ? "rest" : ("p" + n)) + "__" + RT.nextID() + "#");
}
//函数字面量读取器，例如 #(assoc % %2 (* %2 %2))
public static class FnReader extends AFn{
	public Object invoke(Object reader, Object lparen) {
		PushbackReader r = (PushbackReader) reader;
		if(ARG_ENV.deref() != null)
			throw new IllegalStateException("Nested #()s are not allowed");
		try
			{
			Var.pushThreadBindings(
					RT.map(ARG_ENV, PersistentTreeMap.EMPTY));
			unread(r, '(');
			Object form = read(r, true, null, true);

			PersistentVector args = PersistentVector.EMPTY;
			PersistentTreeMap argsyms = (PersistentTreeMap) ARG_ENV.deref();
			ISeq rargs = argsyms.rseq();
			if(rargs != null)
				{
				int higharg = (Integer) ((Map.Entry) rargs.first()).getKey();
				if(higharg > 0)
					{
					for(int i = 1; i <= higharg; ++i)
						{
						Object sym = argsyms.valAt(i);
						if(sym == null)
							sym = garg(i);
						args = args.cons(sym);
						}
					}
				Object restsym = argsyms.valAt(-1);
				if(restsym != null)
					{
					args = args.cons(Compiler._AMP_);
					args = args.cons(restsym);
					}
				}
			return RT.list(Compiler.FN, args, form);
			}
		finally
			{
			Var.popThreadBindings();
			}
	}
}

static Symbol registerArg(int n){
	PersistentTreeMap argsyms = (PersistentTreeMap) ARG_ENV.deref();
	if(argsyms == null)
		{
		throw new IllegalStateException("arg literal not in #()");
		}
	Symbol ret = (Symbol) argsyms.valAt(n);
	if(ret == null)
		{
		ret = garg(n);
		ARG_ENV.set(argsyms.assoc(n, ret));
		}
	return ret;
}

static class ArgReader extends AFn{
	public Object invoke(Object reader, Object pct) {
		PushbackReader r = (PushbackReader) reader;
		if(ARG_ENV.deref() == null)
			{
			return interpretToken(readToken(r, '%'));
			}
		int ch = read1(r);
		unread(r, ch);
		//% alone is first arg
		if(ch == -1 || isWhitespace(ch) || isTerminatingMacro(ch))
			{
			return registerArg(1);
			}
		Object n = read(r, true, null, true);
		if(n.equals(Compiler._AMP_))
			return registerArg(-1);
		if(!(n instanceof Number))
			throw new IllegalStateException("arg literal must be %, %& or %integer");
		return registerArg(((Number) n).intValue());
	}
}
//读取带有元数据的形式，例如 ^{:x 1 :y 2} zz
public static class MetaReader extends AFn{
	public Object invoke(Object reader, Object caret) {
		PushbackReader r = (PushbackReader) reader;
		int line = -1;
		int column = -1;
		if(r instanceof LineNumberingPushbackReader)
			{
			line = ((LineNumberingPushbackReader) r).getLineNumber();
			column = ((LineNumberingPushbackReader) r).getColumnNumber()-1;
			}
		Object meta = read(r, true, null, true);//读取元数据
		if(meta instanceof Symbol || meta instanceof String)//如果是符号或字符串，视为类型提示
			meta = RT.map(RT.TAG_KEY, meta);
		else if (meta instanceof Keyword)//如果是关键字，视为{:k true}
			meta = RT.map(meta, RT.T);
		else if(!(meta instanceof IPersistentMap))
			throw new IllegalArgumentException("Metadata must be Symbol,Keyword,String or Map");

		Object o = read(r, true, null, true);//读取对象，该对象必须能携带元数据，即实现了IMeta接口
		if(o instanceof IMeta)
			{
			if(line != -1 && o instanceof ISeq)//对象是序列类型
				{
				meta = ((IPersistentMap) meta).assoc(RT.LINE_KEY, line).assoc(RT.COLUMN_KEY, column);
				}
			if(o instanceof IReference)//对象是引用类型
				{
				((IReference)o).resetMeta((IPersistentMap) meta);
				return o;//hxzon注意：引用类型直接替换掉原有的元数据，而序列类型则合并自身已有的元数据：^:x ^:y o
				}
			Object ometa = RT.meta(o);//对象已携带的元数据，例如^:x ^:y o ，首先得到带有^:y 的 o ，现在合并^:x
			for(ISeq s = RT.seq(meta); s != null; s = s.next()) {//合并meta到ometa
			IMapEntry kv = (IMapEntry) s.first();
			ometa = RT.assoc(ometa, kv.getKey(), kv.getValue());
			}
			return ((IObj) o).withMeta((IPersistentMap) ometa);
			}
		else
			throw new IllegalArgumentException("Metadata can only be applied to IMetas");
	}

}
//语法引述，反引号
public static class SyntaxQuoteReader extends AFn{
	public Object invoke(Object reader, Object backquote) {
		PushbackReader r = (PushbackReader) reader;
		try
			{
			Var.pushThreadBindings(
					RT.map(GENSYM_ENV, PersistentHashMap.EMPTY));

			Object form = read(r, true, null, true);
			return syntaxQuote(form);
			}
		finally
			{
			Var.popThreadBindings();
			}
	}
	//语法引述
	static Object syntaxQuote(Object form) {
		Object ret;
		if(Compiler.isSpecial(form))
			ret = RT.list(Compiler.QUOTE, form);
		else if(form instanceof Symbol)
			{
			Symbol sym = (Symbol) form;
			if(sym.ns == null && sym.name.endsWith("#"))//自动转成全局唯一符号
				{
				IPersistentMap gmap = (IPersistentMap) GENSYM_ENV.deref();
				if(gmap == null)
					throw new IllegalStateException("Gensym literal not in syntax-quote");
				Symbol gs = (Symbol) gmap.valAt(sym);
				if(gs == null)//该符号如果还未转成全局唯一符号
					GENSYM_ENV.set(gmap.assoc(sym, gs = Symbol.intern(null,
					                                                  sym.name.substring(0, sym.name.length() - 1)
					                                                  + "__" + RT.nextID() + "__auto__")));
				sym = gs;
				}
			else if(sym.ns == null && sym.name.endsWith("."))//java构造函数
				{
				Symbol csym = Symbol.intern(null, sym.name.substring(0, sym.name.length() - 1));
				csym = Compiler.resolveSymbol(csym);
				sym = Symbol.intern(null, csym.name.concat("."));
				}
			else if(sym.ns == null && sym.name.startsWith("."))//java方法
				{
				// Simply quote method names.
				}
			else
				{
				Object maybeClass = null;
				if(sym.ns != null)
					maybeClass = Compiler.currentNS().getMapping(
							Symbol.intern(null, sym.ns));//是否是导入到当前命名空间的类的“短名”
				if(maybeClass instanceof Class)
					{
					// Classname/foo -> package.qualified.Classname/foo
					sym = Symbol.intern(
							((Class)maybeClass).getName(), sym.name);//恢复成类的完整名（hxzon：注意）
					}
				else
					sym = Compiler.resolveSymbol(sym);
				}
			ret = RT.list(Compiler.QUOTE, sym);
			}
		else if(isUnquote(form))//如果是取消引述
			return RT.second(form);
		else if(isUnquoteSplicing(form))
			throw new IllegalStateException("splice not in list");
		else if(form instanceof IPersistentCollection)
			{
			if(form instanceof IRecord)
				ret = form;
			else if(form instanceof IPersistentMap)
				{//生成 (apply hashmap (seq (concat k1 v1 k2 v2)))
				IPersistentVector keyvals = flattenMap(form);
				ret = RT.list(APPLY, HASHMAP, RT.list(SEQ, RT.cons(CONCAT, sqExpandList(keyvals.seq()))));
				}
			else if(form instanceof IPersistentVector)
				{
				ret = RT.list(APPLY, VECTOR, RT.list(SEQ, RT.cons(CONCAT, sqExpandList(((IPersistentVector) form).seq()))));
				}
			else if(form instanceof IPersistentSet)
				{
				ret = RT.list(APPLY, HASHSET, RT.list(SEQ, RT.cons(CONCAT, sqExpandList(((IPersistentSet) form).seq()))));
				}
			else if(form instanceof ISeq || form instanceof IPersistentList)
				{
				ISeq seq = RT.seq(form);
				if(seq == null)
					ret = RT.cons(LIST,null);
				else
					ret = RT.list(SEQ, RT.cons(CONCAT, sqExpandList(seq)));
				}
			else
				throw new UnsupportedOperationException("Unknown Collection type");
			}
		else if(form instanceof Keyword
		        || form instanceof Number
		        || form instanceof Character
		        || form instanceof String)
			ret = form;
		else
			ret = RT.list(Compiler.QUOTE, form);

		if(form instanceof IObj && RT.meta(form) != null)//如果含有元数据（除掉行列号）
			{
			//filter line and column numbers
			IPersistentMap newMeta = ((IObj) form).meta().without(RT.LINE_KEY).without(RT.COLUMN_KEY);
			if(newMeta.count() > 0)
				return RT.list(WITH_META, ret, syntaxQuote(((IObj) form).meta()));//hxzon注意：对元数据进行语法引述
			}
		return ret;
	}

	private static ISeq sqExpandList(ISeq seq) {
		PersistentVector ret = PersistentVector.EMPTY;
		for(; seq != null; seq = seq.next())
			{
			Object item = seq.first();
			if(isUnquote(item))//取消语法引述
				ret = ret.cons(RT.list(LIST, RT.second(item)));
			else if(isUnquoteSplicing(item))//取消语法引述并拼接
				ret = ret.cons(RT.second(item));
			else//语法引述
				ret = ret.cons(RT.list(LIST, syntaxQuote(item)));
			}
		return ret.seq();
	}

	private static IPersistentVector flattenMap(Object form){
		IPersistentVector keyvals = PersistentVector.EMPTY;
		for(ISeq s = RT.seq(form); s != null; s = s.next())
			{
			IMapEntry e = (IMapEntry) s.first();
			keyvals = (IPersistentVector) keyvals.cons(e.key());
			keyvals = (IPersistentVector) keyvals.cons(e.val());
			}
		return keyvals;
	}

}

static boolean isUnquoteSplicing(Object form){
	return form instanceof ISeq && Util.equals(RT.first(form),UNQUOTE_SPLICING);
}

static boolean isUnquote(Object form){
	return form instanceof ISeq && Util.equals(RT.first(form),UNQUOTE);
}
//取消语法引述，波浪号
static class UnquoteReader extends AFn{
	public Object invoke(Object reader, Object comma) {
		PushbackReader r = (PushbackReader) reader;
		int ch = read1(r);
		if(ch == -1)
			throw Util.runtimeException("EOF while reading character");
		if(ch == '@')
			{
			Object o = read(r, true, null, true);
			return RT.list(UNQUOTE_SPLICING, o);//取消语法引述并拼接
			}
		else
			{
			unread(r, ch);
			Object o = read(r, true, null, true);
			return RT.list(UNQUOTE, o);//取消语法引述
			}
	}

}

public static class CharacterReader extends AFn{
	public Object invoke(Object reader, Object backslash) {
		PushbackReader r = (PushbackReader) reader;
		int ch = read1(r);
		if(ch == -1)
			throw Util.runtimeException("EOF while reading character");
		String token = readToken(r, (char) ch);
		if(token.length() == 1)
			return Character.valueOf(token.charAt(0));
		else if(token.equals("newline"))
			return '\n';
		else if(token.equals("space"))
			return ' ';
		else if(token.equals("tab"))
			return '\t';
		else if(token.equals("backspace"))
			return '\b';
		else if(token.equals("formfeed"))
			return '\f';
		else if(token.equals("return"))
			return '\r';
		else if(token.startsWith("u"))
			{
			char c = (char) readUnicodeChar(token, 1, 4, 16);
			if(c >= '\uD800' && c <= '\uDFFF') // surrogate code unit?
				throw Util.runtimeException("Invalid character constant: \\u" + Integer.toString(c, 16));
			return c;
			}
		else if(token.startsWith("o"))
			{
			int len = token.length() - 1;
			if(len > 3)
				throw Util.runtimeException("Invalid octal escape sequence length: " + len);
			int uc = readUnicodeChar(token, 1, len, 8);
			if(uc > 0377)
				throw Util.runtimeException("Octal escape sequence must be in range [0, 377].");
			return (char) uc;
			}
		throw Util.runtimeException("Unsupported character: \\" + token);
	}

}
//列表读取器
public static class ListReader extends AFn{
	public Object invoke(Object reader, Object leftparen) {
		PushbackReader r = (PushbackReader) reader;
		int line = -1;
		int column = -1;
		if(r instanceof LineNumberingPushbackReader)
			{
			line = ((LineNumberingPushbackReader) r).getLineNumber();
			column = ((LineNumberingPushbackReader) r).getColumnNumber()-1;
			}
		List list = readDelimitedList(')', r, true);//向前读取，直到遇到右括号
		if(list.isEmpty())
			return PersistentList.EMPTY;
		IObj s = (IObj) PersistentList.create(list);
//		IObj s = (IObj) RT.seq(list);
		if(line != -1)
			{
			return s.withMeta(RT.map(RT.LINE_KEY, line, RT.COLUMN_KEY, column));
			}
		else
			return s;
	}

}

/*
static class CtorReader extends AFn{
	static final Symbol cls = Symbol.intern("class");

	public Object invoke(Object reader, Object leftangle) {
		PushbackReader r = (PushbackReader) reader;
		// #<class classname>
		// #<classname args*>
		// #<classname/staticMethod args*>
		List list = readDelimitedList('>', r, true);
		if(list.isEmpty())
			throw Util.runtimeException("Must supply 'class', classname or classname/staticMethod");
		Symbol s = (Symbol) list.get(0);
		Object[] args = list.subList(1, list.size()).toArray();
		if(s.equals(cls))
			{
			return RT.classForName(args[0].toString());
			}
		else if(s.ns != null) //static method
			{
			String classname = s.ns;
			String method = s.name;
			return Reflector.invokeStaticMethod(classname, method, args);
			}
		else
			{
			return Reflector.invokeConstructor(RT.classForName(s.name), args);
			}
	}
}
*/
// #= 读取期求值？
public static class EvalReader extends AFn{
	public Object invoke(Object reader, Object eq) {
		if (!RT.booleanCast(RT.READEVAL.deref()))
			{
			throw Util.runtimeException("EvalReader not allowed when *read-eval* is false.");
			}

		PushbackReader r = (PushbackReader) reader;
		Object o = read(r, true, null, true);
		if(o instanceof Symbol)
			{
			return RT.classForName(o.toString());
			}
		else if(o instanceof IPersistentList)
			{
			Symbol fs = (Symbol) RT.first(o);
			if(fs.equals(THE_VAR))//(var a) 取得Var本身
				{
				Symbol vs = (Symbol) RT.second(o);
				return RT.var(vs.ns, vs.name);  //Compiler.resolve((Symbol) RT.second(o),true);
				}
			if(fs.name.endsWith("."))//java构造函数
				{
				Object[] args = RT.toArray(RT.next(o));
				return Reflector.invokeConstructor(RT.classForName(fs.name.substring(0, fs.name.length() - 1)), args);
				}
			if(Compiler.namesStaticMember(fs))//java静态方法
				{
				Object[] args = RT.toArray(RT.next(o));
				return Reflector.invokeStaticMethod(fs.ns, fs.name, args);
				}
			Object v = Compiler.maybeResolveIn(Compiler.currentNS(), fs);
			if(v instanceof Var)
				{
				return ((IFn) v).applyTo(RT.next(o));
				}
			throw Util.runtimeException("Can't resolve " + fs);
			}
		else
			throw new IllegalArgumentException("Unsupported #= form");
	}
}

//static class ArgVectorReader extends AFn{
//	public Object invoke(Object reader, Object leftparen) {
//		PushbackReader r = (PushbackReader) reader;
//		return ArgVector.create(readDelimitedList('|', r, true));
//	}
//
//}

public static class VectorReader extends AFn{
	public Object invoke(Object reader, Object leftparen) {
		PushbackReader r = (PushbackReader) reader;
		return LazilyPersistentVector.create(readDelimitedList(']', r, true));
	}

}

public static class MapReader extends AFn{
	public Object invoke(Object reader, Object leftparen) {
		PushbackReader r = (PushbackReader) reader;
		Object[] a = readDelimitedList('}', r, true).toArray();
		if((a.length & 1) == 1)
			throw Util.runtimeException("Map literal must contain an even number of forms");
		return RT.map(a);
	}

}

public static class SetReader extends AFn{
	public Object invoke(Object reader, Object leftbracket) {
		PushbackReader r = (PushbackReader) reader;
		return PersistentHashSet.createWithCheck(readDelimitedList('}', r, true));
	}

}

public static class UnmatchedDelimiterReader extends AFn{
	public Object invoke(Object reader, Object rightdelim) {
		throw Util.runtimeException("Unmatched delimiter: " + rightdelim);
	}

}

public static class UnreadableReader extends AFn{
	public Object invoke(Object reader, Object leftangle) {
		throw Util.runtimeException("Unreadable form");
	}
}
//向前读取，直到遇到指定的delim字符
public static List readDelimitedList(char delim, PushbackReader r, boolean isRecursive) {
	final int firstline =
			(r instanceof LineNumberingPushbackReader) ?
			((LineNumberingPushbackReader) r).getLineNumber() : -1;

	ArrayList a = new ArrayList();

	for(; ;)
		{
		int ch = read1(r);

		while(isWhitespace(ch))//忽略空白符
			ch = read1(r);

		if(ch == -1)
			{
			if(firstline < 0)
				throw Util.runtimeException("EOF while reading");
			else
				throw Util.runtimeException("EOF while reading, starting at line " + firstline);
			}

		if(ch == delim)//遇到指定的结束符，停止
			break;

		IFn macroFn = getMacro(ch);
		if(macroFn != null)//读取器宏
			{
			Object mret = macroFn.invoke(r, (char) ch);
			//no op macros return the reader
			if(mret != r)
				a.add(mret);
			}
		else
			{
			unread(r, ch);

			Object o = read(r, true, null, isRecursive);
			if(o != r)
				a.add(o);
			}
		}


	return a;
}
//记录字面量，或者reader tag：#p.classname 或 #xyz
public static class CtorReader extends AFn{
	public Object invoke(Object reader, Object firstChar){
		PushbackReader r = (PushbackReader) reader;
		Object name = read(r, true, null, false);
		if (!(name instanceof Symbol))
			throw new RuntimeException("Reader tag must be a symbol");
		Symbol sym = (Symbol)name;
		return sym.getName().contains(".") ? readRecord(r, sym) : readTagged(r, sym);
	}

	private Object readTagged(PushbackReader reader, Symbol tag){
		Object o = read(reader, true, null, true);

		ILookup data_readers = (ILookup)RT.DATA_READERS.deref();
		IFn data_reader = (IFn)RT.get(data_readers, tag);
		if(data_reader == null){
		data_readers = (ILookup)RT.DEFAULT_DATA_READERS.deref();
		data_reader = (IFn)RT.get(data_readers, tag);
		if(data_reader == null){
		IFn default_reader = (IFn)RT.DEFAULT_DATA_READER_FN.deref();
		if(default_reader != null)
			return default_reader.invoke(tag, o);
		else
			throw new RuntimeException("No reader function for tag " + tag.toString());
		}
		}

		return data_reader.invoke(o);
	}

	private Object readRecord(PushbackReader r, Symbol recordName){
        boolean readeval = RT.booleanCast(RT.READEVAL.deref());

	    if(!readeval)
		    {
		    throw Util.runtimeException("Record construction syntax can only be used when *read-eval* == true");
		    }

		Class recordClass = RT.classForNameNonLoading(recordName.toString());

		char endch;
		boolean shortForm = true;
		int ch = read1(r);

		// flush whitespace
		while(isWhitespace(ch))
			ch = read1(r);

		// A defrecord ctor can take two forms. Check for map->R version first.
		if(ch == '{')
			{
			endch = '}';
			shortForm = false;
			}
		else if (ch == '[')
			endch = ']';
		else
			throw Util.runtimeException("Unreadable constructor form starting with \"#" + recordName + (char) ch + "\"");

		Object[] recordEntries = readDelimitedList(endch, r, true).toArray();
		Object ret = null;
		Constructor[] allctors = ((Class)recordClass).getConstructors();

		if(shortForm)
			{
			boolean ctorFound = false;
			for (Constructor ctor : allctors)
				if(ctor.getParameterTypes().length == recordEntries.length)
					ctorFound = true;

			if(!ctorFound)
				throw Util.runtimeException("Unexpected number of constructor arguments to " + recordClass.toString() + ": got " + recordEntries.length);

			ret = Reflector.invokeConstructor(recordClass, recordEntries);
			}
		else
			{

			IPersistentMap vals = RT.map(recordEntries);
			for(ISeq s = RT.keys(vals); s != null; s = s.next())
				{
				if(!(s.first() instanceof Keyword))
					throw Util.runtimeException("Unreadable defrecord form: key must be of type clojure.lang.Keyword, got " + s.first().toString());
				}
			ret = Reflector.invokeStaticMethod(recordClass, "create", new Object[]{vals});
			}

		return ret;
	}
}

/*
public static void main(String[] args) throws Exception{
	//RT.init();
	PushbackReader rdr = new PushbackReader( new java.io.StringReader( "(+ 21 21)" ) );
	Object input = LispReader.read(rdr, false, new Object(), false );
	System.out.println(Compiler.eval(input));
}

public static void main(String[] args){
	LineNumberingPushbackReader r = new LineNumberingPushbackReader(new InputStreamReader(System.in));
	OutputStreamWriter w = new OutputStreamWriter(System.out);
	Object ret = null;
	try
		{
		for(; ;)
			{
			ret = LispReader.read(r, true, null, false);
			RT.print(ret, w);
			w.write('\n');
			if(ret != null)
				w.write(ret.getClass().toString());
			w.write('\n');
			w.flush();
			}
		}
	catch(Exception e)
		{
		e.printStackTrace();
		}
}
 */

}

