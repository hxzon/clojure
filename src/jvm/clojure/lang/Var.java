/**
 *   Copyright (c) Rich Hickey. All rights reserved.
 *   The use and distribution terms for this software are covered by the
 *   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
 *   which can be found in the file epl-v10.html at the root of this distribution.
 *   By using this software in any fashion, you are agreeing to be bound by
 * 	 the terms of this license.
 *   You must not remove this notice, or any other, from this software.
 **/

/* rich Jul 31, 2007 */

package clojure.lang;

import java.util.concurrent.atomic.AtomicBoolean;

//继承了AReference，所以可携带元数据
public final class Var extends ARef implements IFn, IRef, Settable{

static class TBox{//线程盒，存储线程值

volatile Object val;
final Thread thread;

public TBox(Thread t, Object val){
	this.thread = t;
	this.val = val;
}
}

static public class Unbound extends AFn{//未绑定的Var
	final public Var v;

	public Unbound(Var v){
		this.v = v;
	}

	public String toString(){
		return "Unbound: " + v;
	}

	public Object throwArity(int n){
		throw new IllegalStateException("Attempting to call unbound fn: " + v);
	}
}

static class Frame{//帧
	final static Frame TOP = new Frame(PersistentHashMap.EMPTY, null);
	//Var->TBox
	Associative bindings;//本帧的绑定值
	//Var->val
//	Associative frameBindings;
	Frame prev;//前一帧

	public Frame(Associative bindings, Frame prev){
//		this.frameBindings = frameBindings;
		this.bindings = bindings;
		this.prev = prev;
	}

    	protected Object clone() {
		return new Frame(this.bindings, null);
    	}

}

static final ThreadLocal<Frame> dvals = new ThreadLocal<Frame>(){//线程本地帧

	protected Frame initialValue(){
		return Frame.TOP;
	}
};

static public volatile int rev = 0;//根值版本号，每次修改根值，会递增版本号

static Keyword privateKey = Keyword.intern(null, "private");
static IPersistentMap privateMeta = new PersistentArrayMap(new Object[]{privateKey, Boolean.TRUE});
static Keyword macroKey = Keyword.intern(null, "macro");
static Keyword nameKey = Keyword.intern(null, "name");
static Keyword nsKey = Keyword.intern(null, "ns");
//static Keyword tagKey = Keyword.intern(null, "tag");

volatile Object root;//根值

volatile boolean dynamic = false;//是否是动态Var
transient final AtomicBoolean threadBound;//是否线程绑定
public final Symbol sym;//本Var的符号（名字）
public final Namespace ns;//本Var所在的命名空间

//IPersistentMap _meta;

public static Object getThreadBindingFrame(){
	return dvals.get();
}

public static Object cloneThreadBindingFrame(){
	return dvals.get().clone();
}

public static void resetThreadBindingFrame(Object frame){
	dvals.set((Frame) frame);
}

public Var setDynamic(){
	this.dynamic = true;
	return this;
}

public Var setDynamic(boolean b){
	this.dynamic = b;
	return this;
}

public final boolean isDynamic(){
	return dynamic;
}
//查找或创建Var
public static Var intern(Namespace ns, Symbol sym, Object root){
	return intern(ns, sym, root, true);
}

public static Var intern(Namespace ns, Symbol sym, Object root, boolean replaceRoot){
	Var dvout = ns.intern(sym);
	if(!dvout.hasRoot() || replaceRoot)
		dvout.bindRoot(root);
	return dvout;
}


public String toString(){
	if(ns != null)
		return "#'" + ns.name + "/" + sym;
	return "#<Var: " + (sym != null ? sym.toString() : "--unnamed--") + ">";
}
//查找Var
public static Var find(Symbol nsQualifiedSym){
	if(nsQualifiedSym.ns == null)
		throw new IllegalArgumentException("Symbol must be namespace-qualified");
	Namespace ns = Namespace.find(Symbol.intern(nsQualifiedSym.ns));
	if(ns == null)
		throw new IllegalArgumentException("No such namespace: " + nsQualifiedSym.ns);
	return ns.findInternedVar(Symbol.intern(nsQualifiedSym.name));
}

public static Var intern(Symbol nsName, Symbol sym){
	Namespace ns = Namespace.findOrCreate(nsName);
	return intern(ns, sym);
}
//查找或创建Var，并设为私有
public static Var internPrivate(String nsName, String sym){
	Namespace ns = Namespace.findOrCreate(Symbol.intern(nsName));
	Var ret = intern(ns, Symbol.intern(sym));
	ret.setMeta(privateMeta);
	return ret;
}

public static Var intern(Namespace ns, Symbol sym){
	return ns.intern(sym);
}


public static Var create(){
	return new Var(null, null);
}

public static Var create(Object root){
	return new Var(null, null, root);
}

Var(Namespace ns, Symbol sym){
	this.ns = ns;
	this.sym = sym;
	this.threadBound = new AtomicBoolean(false);
	this.root = new Unbound(this);//未绑定，所以根值是一个不可调用的函数
	setMeta(PersistentHashMap.EMPTY);
}

Var(Namespace ns, Symbol sym, Object root){
	this(ns, sym);
	this.root = root;
	++rev;
}
//是否已绑定：有根值，或者本线程绑定了值
public boolean isBound(){
	return hasRoot() || (threadBound.get() && dvals.get().bindings.containsKey(this));
}
//优先线程绑定值，再根值
final public Object get(){
	if(!threadBound.get())
		return root;
	return deref();
}

final public Object deref(){
	TBox b = getThreadBinding();
	if(b != null)
		return b.val;
	return root;
}

public void setValidator(IFn vf){
	if(hasRoot())
		validate(vf, root);
	validator = vf;
}

public Object alter(IFn fn, ISeq args) {
	set(fn.applyTo(RT.cons(deref(), args)));
	return this;
}
//设置线程绑定值
public Object set(Object val){
	validate(getValidator(), val);
	TBox b = getThreadBinding();
	if(b != null)
		{
		if(Thread.currentThread() != b.thread)
			throw new IllegalStateException(String.format("Can't set!: %s from non-binding thread", sym));
		return (b.val = val);
		}
	throw new IllegalStateException(String.format("Can't change/establish root binding of: %s with set", sym));
}

public Object doSet(Object val)  {
    return set(val);
    }

public Object doReset(Object val)  {
    bindRoot(val);
    return val;
    }

public void setMeta(IPersistentMap m) {
    //ensure these basis keys
    resetMeta(m.assoc(nameKey, sym).assoc(nsKey, ns));
}

public void setMacro() {
    alterMeta(assoc, RT.list(macroKey, RT.T));
}

public boolean isMacro(){
	return RT.booleanCast(meta().valAt(macroKey));
}

//public void setExported(boolean state){
//	_meta = _meta.assoc(privateKey, state);
//}

public boolean isPublic(){
	return !RT.booleanCast(meta().valAt(privateKey));
}

final public Object getRawRoot(){
		return root;
}
//类型提示
public Object getTag(){
	return meta().valAt(RT.TAG_KEY);
}

public void setTag(Symbol tag) {
    alterMeta(assoc, RT.list(RT.TAG_KEY, tag));
}
//是否有根值
final public boolean hasRoot(){
	return !(root instanceof Unbound);
}
//设置新根值，移除宏标记
//binding root always clears macro flag
synchronized public void bindRoot(Object root){
	validate(getValidator(), root);
	Object oldroot = this.root;
	this.root = root;
	++rev;
        alterMeta(dissoc, RT.list(macroKey));
    notifyWatches(oldroot,this.root);
}
//设置新根值
synchronized void swapRoot(Object root){
	validate(getValidator(), root);
	Object oldroot = this.root;
	this.root = root;
	++rev;
    notifyWatches(oldroot,root);
}
//设置为未绑定
synchronized public void unbindRoot(){
	this.root = new Unbound(this);
	++rev;
}

synchronized public void commuteRoot(IFn fn) {
	Object newRoot = fn.invoke(root);
	validate(getValidator(), newRoot);
	Object oldroot = root;
	this.root = newRoot;
	++rev;
    notifyWatches(oldroot,newRoot);
}
//设置新根值，通过指定的函数
synchronized public Object alterRoot(IFn fn, ISeq args) {
	Object newRoot = fn.applyTo(RT.cons(root, args));
	validate(getValidator(), newRoot);
	Object oldroot = root;
	this.root = newRoot;
	++rev;
    notifyWatches(oldroot,newRoot);
	return newRoot;
}
//压入新的一帧（会检查是否是“动态Var”，并标记为“已线程绑定”）
public static void pushThreadBindings(Associative bindings){
	Frame f = dvals.get();
	Associative bmap = f.bindings;
	for(ISeq bs = bindings.seq(); bs != null; bs = bs.next())
		{
		IMapEntry e = (IMapEntry) bs.first();
		Var v = (Var) e.key();
		if(!v.dynamic)
			throw new IllegalStateException(String.format("Can't dynamically bind non-dynamic var: %s/%s", v.ns, v.sym));
		v.validate(v.getValidator(), e.val());
		v.threadBound.set(true);
		bmap = bmap.assoc(v, new TBox(Thread.currentThread(), e.val()));
		}
	dvals.set(new Frame(bmap, f));
}
//弹出当前帧
public static void popThreadBindings(){
    Frame f = dvals.get().prev;
    if (f == null) {
        throw new IllegalStateException("Pop without matching push");
    } else if (f == Frame.TOP) {
        dvals.remove();
    } else {
        dvals.set(f);
    }
}
//获取线程绑定值（最后一帧）
public static Associative getThreadBindings(){
	Frame f = dvals.get();
	IPersistentMap ret = PersistentHashMap.EMPTY;
	for(ISeq bs = f.bindings.seq(); bs != null; bs = bs.next())
		{
		IMapEntry e = (IMapEntry) bs.first();
		Var v = (Var) e.key();
		TBox b = (TBox) e.val();
		ret = ret.assoc(v, b.val);
		}
	return ret;
}
//获取本Var的线程绑定值（最后一帧）
public final TBox getThreadBinding(){
	if(threadBound.get())
		{
		IMapEntry e = dvals.get().bindings.entryAt(this);
		if(e != null)
			return (TBox) e.val();
		}
	return null;
}

final public IFn fn(){
	return (IFn) deref();
}
//调用本Var，当本Var是函数时
public Object call() {
	return invoke();
}

public void run(){
        invoke();
}

public Object invoke() {
	return fn().invoke();
}

public Object invoke(Object arg1) {
    return fn().invoke(Util.ret1(arg1,arg1=null));
}

public Object invoke(Object arg1, Object arg2) {
    return fn().invoke(Util.ret1(arg1,arg1=null),
                       Util.ret1(arg2,arg2=null));
}

public Object invoke(Object arg1, Object arg2, Object arg3) {
    return fn().invoke(Util.ret1(arg1,arg1=null),
                       Util.ret1(arg2,arg2=null),
                       Util.ret1(arg3,arg3=null));
}

public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4) {
    return fn().invoke(Util.ret1(arg1,arg1=null),
                       Util.ret1(arg2,arg2=null),
                       Util.ret1(arg3,arg3=null),
                       Util.ret1(arg4,arg4=null));
}

public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5) {
    return fn().invoke(Util.ret1(arg1,arg1=null),
                       Util.ret1(arg2,arg2=null),
                       Util.ret1(arg3,arg3=null),
                       Util.ret1(arg4,arg4=null),
                       Util.ret1(arg5,arg5=null));
}

public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6) {
    return fn().invoke(Util.ret1(arg1,arg1=null),
                       Util.ret1(arg2,arg2=null),
                       Util.ret1(arg3,arg3=null),
                       Util.ret1(arg4,arg4=null),
                       Util.ret1(arg5,arg5=null),
                       Util.ret1(arg6,arg6=null));
}

public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7)
		{
    return fn().invoke(Util.ret1(arg1,arg1=null),
                       Util.ret1(arg2,arg2=null),
                       Util.ret1(arg3,arg3=null),
                       Util.ret1(arg4,arg4=null),
                       Util.ret1(arg5,arg5=null),
                       Util.ret1(arg6,arg6=null),
                       Util.ret1(arg7,arg7=null));
}

public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7,
                     Object arg8) {
    return fn().invoke(Util.ret1(arg1,arg1=null),
                       Util.ret1(arg2,arg2=null),
                       Util.ret1(arg3,arg3=null),
                       Util.ret1(arg4,arg4=null),
                       Util.ret1(arg5,arg5=null),
                       Util.ret1(arg6,arg6=null),
                       Util.ret1(arg7,arg7=null),
                       Util.ret1(arg8,arg8=null));
}

public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7,
                     Object arg8, Object arg9) {
    return fn().invoke(Util.ret1(arg1,arg1=null),
                       Util.ret1(arg2,arg2=null),
                       Util.ret1(arg3,arg3=null),
                       Util.ret1(arg4,arg4=null),
                       Util.ret1(arg5,arg5=null),
                       Util.ret1(arg6,arg6=null),
                       Util.ret1(arg7,arg7=null),
                       Util.ret1(arg8,arg8=null),
                       Util.ret1(arg9,arg9=null));
}

public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7,
                     Object arg8, Object arg9, Object arg10) {
    return fn().invoke(Util.ret1(arg1,arg1=null),
                       Util.ret1(arg2,arg2=null),
                       Util.ret1(arg3,arg3=null),
                       Util.ret1(arg4,arg4=null),
                       Util.ret1(arg5,arg5=null),
                       Util.ret1(arg6,arg6=null),
                       Util.ret1(arg7,arg7=null),
                       Util.ret1(arg8,arg8=null),
                       Util.ret1(arg9,arg9=null),
                       Util.ret1(arg10,arg10=null));
}

public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7,
                     Object arg8, Object arg9, Object arg10, Object arg11) {
    return fn().invoke(Util.ret1(arg1,arg1=null),
                       Util.ret1(arg2,arg2=null),
                       Util.ret1(arg3,arg3=null),
                       Util.ret1(arg4,arg4=null),
                       Util.ret1(arg5,arg5=null),
                       Util.ret1(arg6,arg6=null),
                       Util.ret1(arg7,arg7=null),
                       Util.ret1(arg8,arg8=null),
                       Util.ret1(arg9,arg9=null),
                       Util.ret1(arg10,arg10=null),
                       Util.ret1(arg11,arg11=null));
}

public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7,
                     Object arg8, Object arg9, Object arg10, Object arg11, Object arg12) {
    return fn().invoke(Util.ret1(arg1,arg1=null),
                       Util.ret1(arg2,arg2=null),
                       Util.ret1(arg3,arg3=null),
                       Util.ret1(arg4,arg4=null),
                       Util.ret1(arg5,arg5=null),
                       Util.ret1(arg6,arg6=null),
                       Util.ret1(arg7,arg7=null),
                       Util.ret1(arg8,arg8=null),
                       Util.ret1(arg9,arg9=null),
                       Util.ret1(arg10,arg10=null),
                       Util.ret1(arg11,arg11=null),
                       Util.ret1(arg12,arg12=null));
}

public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7,
                     Object arg8, Object arg9, Object arg10, Object arg11, Object arg12, Object arg13)
		{
    return fn().invoke(Util.ret1(arg1,arg1=null),
                       Util.ret1(arg2,arg2=null),
                       Util.ret1(arg3,arg3=null),
                       Util.ret1(arg4,arg4=null),
                       Util.ret1(arg5,arg5=null),
                       Util.ret1(arg6,arg6=null),
                       Util.ret1(arg7,arg7=null),
                       Util.ret1(arg8,arg8=null),
                       Util.ret1(arg9,arg9=null),
                       Util.ret1(arg10,arg10=null),
                       Util.ret1(arg11,arg11=null),
                       Util.ret1(arg12,arg12=null),
                       Util.ret1(arg13,arg13=null));
}

public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7,
                     Object arg8, Object arg9, Object arg10, Object arg11, Object arg12, Object arg13, Object arg14)
		{
    return fn().invoke(Util.ret1(arg1,arg1=null),
                       Util.ret1(arg2,arg2=null),
                       Util.ret1(arg3,arg3=null),
                       Util.ret1(arg4,arg4=null),
                       Util.ret1(arg5,arg5=null),
                       Util.ret1(arg6,arg6=null),
                       Util.ret1(arg7,arg7=null),
                       Util.ret1(arg8,arg8=null),
                       Util.ret1(arg9,arg9=null),
                       Util.ret1(arg10,arg10=null),
                       Util.ret1(arg11,arg11=null),
                       Util.ret1(arg12,arg12=null),
                       Util.ret1(arg13,arg13=null),
                       Util.ret1(arg14,arg14=null));
}

public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7,
                     Object arg8, Object arg9, Object arg10, Object arg11, Object arg12, Object arg13, Object arg14,
                     Object arg15) {
    return fn().invoke(Util.ret1(arg1,arg1=null),
                       Util.ret1(arg2,arg2=null),
                       Util.ret1(arg3,arg3=null),
                       Util.ret1(arg4,arg4=null),
                       Util.ret1(arg5,arg5=null),
                       Util.ret1(arg6,arg6=null),
                       Util.ret1(arg7,arg7=null),
                       Util.ret1(arg8,arg8=null),
                       Util.ret1(arg9,arg9=null),
                       Util.ret1(arg10,arg10=null),
                       Util.ret1(arg11,arg11=null),
                       Util.ret1(arg12,arg12=null),
                       Util.ret1(arg13,arg13=null),
                       Util.ret1(arg14,arg14=null),
                       Util.ret1(arg15,arg15=null));
}

public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7,
                     Object arg8, Object arg9, Object arg10, Object arg11, Object arg12, Object arg13, Object arg14,
                     Object arg15, Object arg16) {
    return fn().invoke(Util.ret1(arg1,arg1=null),
                       Util.ret1(arg2,arg2=null),
                       Util.ret1(arg3,arg3=null),
                       Util.ret1(arg4,arg4=null),
                       Util.ret1(arg5,arg5=null),
                       Util.ret1(arg6,arg6=null),
                       Util.ret1(arg7,arg7=null),
                       Util.ret1(arg8,arg8=null),
                       Util.ret1(arg9,arg9=null),
                       Util.ret1(arg10,arg10=null),
                       Util.ret1(arg11,arg11=null),
                       Util.ret1(arg12,arg12=null),
                       Util.ret1(arg13,arg13=null),
                       Util.ret1(arg14,arg14=null),
                       Util.ret1(arg15,arg15=null),
                       Util.ret1(arg16,arg16=null));
}

public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7,
                     Object arg8, Object arg9, Object arg10, Object arg11, Object arg12, Object arg13, Object arg14,
                     Object arg15, Object arg16, Object arg17) {
    return fn().invoke(Util.ret1(arg1,arg1=null),
                       Util.ret1(arg2,arg2=null),
                       Util.ret1(arg3,arg3=null),
                       Util.ret1(arg4,arg4=null),
                       Util.ret1(arg5,arg5=null),
                       Util.ret1(arg6,arg6=null),
                       Util.ret1(arg7,arg7=null),
                       Util.ret1(arg8,arg8=null),
                       Util.ret1(arg9,arg9=null),
                       Util.ret1(arg10,arg10=null),
                       Util.ret1(arg11,arg11=null),
                       Util.ret1(arg12,arg12=null),
                       Util.ret1(arg13,arg13=null),
                       Util.ret1(arg14,arg14=null),
                       Util.ret1(arg15,arg15=null),
                       Util.ret1(arg16,arg16=null),
                       Util.ret1(arg17,arg17=null));
}

public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7,
                     Object arg8, Object arg9, Object arg10, Object arg11, Object arg12, Object arg13, Object arg14,
                     Object arg15, Object arg16, Object arg17, Object arg18) {
    return fn().invoke(Util.ret1(arg1,arg1=null),
                       Util.ret1(arg2,arg2=null),
                       Util.ret1(arg3,arg3=null),
                       Util.ret1(arg4,arg4=null),
                       Util.ret1(arg5,arg5=null),
                       Util.ret1(arg6,arg6=null),
                       Util.ret1(arg7,arg7=null),
                       Util.ret1(arg8,arg8=null),
                       Util.ret1(arg9,arg9=null),
                       Util.ret1(arg10,arg10=null),
                       Util.ret1(arg11,arg11=null),
                       Util.ret1(arg12,arg12=null),
                       Util.ret1(arg13,arg13=null),
                       Util.ret1(arg14,arg14=null),
                       Util.ret1(arg15,arg15=null),
                       Util.ret1(arg16,arg16=null),
                       Util.ret1(arg17,arg17=null),
                       Util.ret1(arg18,arg18=null));
}

public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7,
                     Object arg8, Object arg9, Object arg10, Object arg11, Object arg12, Object arg13, Object arg14,
                     Object arg15, Object arg16, Object arg17, Object arg18, Object arg19) {
    return fn().invoke(Util.ret1(arg1,arg1=null),
                       Util.ret1(arg2,arg2=null),
                       Util.ret1(arg3,arg3=null),
                       Util.ret1(arg4,arg4=null),
                       Util.ret1(arg5,arg5=null),
                       Util.ret1(arg6,arg6=null),
                       Util.ret1(arg7,arg7=null),
                       Util.ret1(arg8,arg8=null),
                       Util.ret1(arg9,arg9=null),
                       Util.ret1(arg10,arg10=null),
                       Util.ret1(arg11,arg11=null),
                       Util.ret1(arg12,arg12=null),
                       Util.ret1(arg13,arg13=null),
                       Util.ret1(arg14,arg14=null),
                       Util.ret1(arg15,arg15=null),
                       Util.ret1(arg16,arg16=null),
                       Util.ret1(arg17,arg17=null),
                       Util.ret1(arg18,arg18=null),
                       Util.ret1(arg19,arg19=null));
}

public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7,
                     Object arg8, Object arg9, Object arg10, Object arg11, Object arg12, Object arg13, Object arg14,
                     Object arg15, Object arg16, Object arg17, Object arg18, Object arg19, Object arg20)
		{
    return fn().invoke(Util.ret1(arg1,arg1=null),
                       Util.ret1(arg2,arg2=null),
                       Util.ret1(arg3,arg3=null),
                       Util.ret1(arg4,arg4=null),
                       Util.ret1(arg5,arg5=null),
                       Util.ret1(arg6,arg6=null),
                       Util.ret1(arg7,arg7=null),
                       Util.ret1(arg8,arg8=null),
                       Util.ret1(arg9,arg9=null),
                       Util.ret1(arg10,arg10=null),
                       Util.ret1(arg11,arg11=null),
                       Util.ret1(arg12,arg12=null),
                       Util.ret1(arg13,arg13=null),
                       Util.ret1(arg14,arg14=null),
                       Util.ret1(arg15,arg15=null),
                       Util.ret1(arg16,arg16=null),
                       Util.ret1(arg17,arg17=null),
                       Util.ret1(arg18,arg18=null),
                       Util.ret1(arg19,arg19=null),
                       Util.ret1(arg20,arg20=null));
}

public Object invoke(Object arg1, Object arg2, Object arg3, Object arg4, Object arg5, Object arg6, Object arg7,
                     Object arg8, Object arg9, Object arg10, Object arg11, Object arg12, Object arg13, Object arg14,
                     Object arg15, Object arg16, Object arg17, Object arg18, Object arg19, Object arg20,
                     Object... args)
		{
    return fn().invoke(Util.ret1(arg1,arg1=null),
                       Util.ret1(arg2,arg2=null),
                       Util.ret1(arg3,arg3=null),
                       Util.ret1(arg4,arg4=null),
                       Util.ret1(arg5,arg5=null),
                       Util.ret1(arg6,arg6=null),
                       Util.ret1(arg7,arg7=null),
                       Util.ret1(arg8,arg8=null),
                       Util.ret1(arg9,arg9=null),
                       Util.ret1(arg10,arg10=null),
                       Util.ret1(arg11,arg11=null),
                       Util.ret1(arg12,arg12=null),
                       Util.ret1(arg13,arg13=null),
                       Util.ret1(arg14,arg14=null),
                       Util.ret1(arg15,arg15=null),
                       Util.ret1(arg16,arg16=null),
                       Util.ret1(arg17,arg17=null),
                       Util.ret1(arg18,arg18=null),
                       Util.ret1(arg19,arg19=null),
                       Util.ret1(arg20,arg20=null),
                       (Object[])Util.ret1(args, args=null));
}

public Object applyTo(ISeq arglist) {
	return AFn.applyToHelper(this, arglist);
}

static IFn assoc = new AFn(){
    @Override
    public Object invoke(Object m, Object k, Object v)  {
        return RT.assoc(m, k, v);
    }
};
static IFn dissoc = new AFn() {
    @Override
    public Object invoke(Object c, Object k)  {
            return RT.dissoc(c, k);
    }
};
}
