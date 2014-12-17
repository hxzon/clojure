/**
 *   Copyright (c) Rich Hickey. All rights reserved.
 *   The use and distribution terms for this software are covered by the
 *   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
 *   which can be found in the file epl-v10.html at the root of this distribution.
 *   By using this software in any fashion, you are agreeing to be bound by
 * 	 the terms of this license.
 *   You must not remove this notice, or any other, from this software.
 **/

/* rich Jul 26, 2007 */

package clojure.lang;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;

@SuppressWarnings({"SynchronizeOnNonFinalField"})
public class LockingTransaction{

public static final int RETRY_LIMIT = 10000;
public static final int LOCK_WAIT_MSECS = 100;
public static final long BARGE_WAIT_NANOS = 10 * 1000000;
//public static int COMMUTE_RETRY_LIMIT = 10;
//事务状态
static final int RUNNING = 0;
static final int COMMITTING = 1;
static final int RETRY = 2;
static final int KILLED = 3;
static final int COMMITTED = 4;

final static ThreadLocal<LockingTransaction> transaction = new ThreadLocal<LockingTransaction>();


static class RetryEx extends Error{
}

static class AbortException extends Exception{
}

public static class Info{
	final AtomicInteger status;//事务状态
	final long startPoint;
	final CountDownLatch latch;


	public Info(int status, long startPoint){
		this.status = new AtomicInteger(status);
		this.startPoint = startPoint;
		this.latch = new CountDownLatch(1);
	}

	public boolean running(){
		int s = status.get();
		return s == RUNNING || s == COMMITTING;
	}
}

static class CFn{
	final IFn fn;
	final ISeq args;

	public CFn(IFn fn, ISeq args){
		this.fn = fn;
		this.args = args;
	}
}
//total order on transactions
//transactions will consume a point for init, for each retry, and on commit if writing
//在事务中的序号
//事务每次初始化，每次重试，每次写提交，都会消耗一个“检查点”
final private static AtomicLong lastPoint = new AtomicLong();
//设置“读取点”
void getReadPoint(){
	readPoint = lastPoint.incrementAndGet();
}

long getCommitPoint(){
	return lastPoint.incrementAndGet();
}

void stop(int status){
	if(info != null)
		{
		synchronized(info)
			{
			info.status.set(status);
			info.latch.countDown();
			}
		info = null;
		vals.clear();
		sets.clear();
		commutes.clear();
		//actions.clear();
		}
}


Info info;
long readPoint;//读取点
long startPoint;//事务开始点
long startTime;//事务开始时间
final RetryEx retryex = new RetryEx();
//存储所有 agent 动作，在事务提交时，才执行。
final ArrayList<Agent.Action> actions = new ArrayList<Agent.Action>();
//记录所有被更新的 ref
final HashMap<Ref, Object> vals = new HashMap<Ref, Object>();
//记录所有用到的 ref
final HashSet<Ref> sets = new HashSet<Ref>();
//存储所有 commute ，在事务提交时，会重新执行一遍
//在 commute 之后，不能再更新该 ref
final TreeMap<Ref, ArrayList<CFn>> commutes = new TreeMap<Ref, ArrayList<CFn>>();
//记录所有被 ensure 的 ref
final HashSet<Ref> ensures = new HashSet<Ref>();   //all hold readLock


void tryWriteLock(Ref ref){
	try
		{
		if(!ref.lock.writeLock().tryLock(LOCK_WAIT_MSECS, TimeUnit.MILLISECONDS))
			throw retryex;
		}
	catch(InterruptedException e)
		{
		throw retryex;
		}
}

//returns the most recent val
Object lock(Ref ref){
	//can't upgrade readLock, so release it
	releaseIfEnsured(ref);

	boolean unlocked = true;
	try
		{
		tryWriteLock(ref);
		unlocked = false;

		if(ref.tvals != null && ref.tvals.point > readPoint)
			throw retryex;
		Info refinfo = ref.tinfo;

		//write lock conflict
		if(refinfo != null && refinfo != info && refinfo.running())
			{
			if(!barge(refinfo))
				{
				ref.lock.writeLock().unlock();
				unlocked = true;
				return blockAndBail(refinfo);
				}
			}
		ref.tinfo = info;
		return ref.tvals == null ? null : ref.tvals.val;
		}
	finally
		{
		if(!unlocked)
			ref.lock.writeLock().unlock();
		}
}

private Object blockAndBail(Info refinfo){
//stop prior to blocking
	stop(RETRY);
	try
		{
		refinfo.latch.await(LOCK_WAIT_MSECS, TimeUnit.MILLISECONDS);
		}
	catch(InterruptedException e)
		{
		//ignore
		}
	throw retryex;
}

private void releaseIfEnsured(Ref ref){
	if(ensures.contains(ref))
		{
		ensures.remove(ref);
		ref.lock.readLock().unlock();
		}
}

void abort() throws AbortException{
	stop(KILLED);
	throw new AbortException();
}

private boolean bargeTimeElapsed(){
	return System.nanoTime() - startTime > BARGE_WAIT_NANOS;
}

private boolean barge(Info refinfo){
	boolean barged = false;
	//if this transaction is older
	//  try to abort the other
	if(bargeTimeElapsed() && startPoint < refinfo.startPoint)
		{
        barged = refinfo.status.compareAndSet(RUNNING, KILLED);
        if(barged)
            refinfo.latch.countDown();
		}
	return barged;
}
//获取所在事务（没有事务则抛出异常）
static LockingTransaction getEx(){
	LockingTransaction t = transaction.get();
	if(t == null || t.info == null)
		throw new IllegalStateException("No transaction running");
	return t;
}

static public boolean isRunning(){
	return getRunning() != null;
}
//获取所在事务（可能不在事务中）
static LockingTransaction getRunning(){
	LockingTransaction t = transaction.get();
	if(t == null || t.info == null)
		return null;
	return t;
}
//在事务中执行操作
static public Object runInTransaction(Callable fn) throws Exception{
	LockingTransaction t = transaction.get();
	Object ret;
	if(t == null) {
		transaction.set(t = new LockingTransaction());
		try {
			ret = t.run(fn);
		} finally {
			transaction.remove();
		}
	} else {
		if(t.info != null) {
			ret = fn.call();
		} else {
			ret = t.run(fn);
		}
	}

	return ret;
}

static class Notify{
	final public Ref ref;
	final public Object oldval;
	final public Object newval;

	Notify(Ref ref, Object oldval, Object newval){
		this.ref = ref;
		this.oldval = oldval;
		this.newval = newval;
	}
}

Object run(Callable fn) throws Exception{
	boolean done = false;
	Object ret = null;
	ArrayList<Ref> locked = new ArrayList<Ref>();
	ArrayList<Notify> notify = new ArrayList<Notify>();

	for(int i = 0; !done && i < RETRY_LIMIT; i++)
		{
		try
			{
			getReadPoint();//设置读取点
			if(i == 0)
				{
				startPoint = readPoint;//设置开始点
				startTime = System.nanoTime();
				}
			info = new Info(RUNNING, startPoint);
			ret = fn.call();
			//make sure no one has killed us before this point, and can't from now on
			if(info.status.compareAndSet(RUNNING, COMMITTING))
				{
				for(Map.Entry<Ref, ArrayList<CFn>> e : commutes.entrySet())
					{
					Ref ref = e.getKey();
					if(sets.contains(ref)) continue;
					
					boolean wasEnsured = ensures.contains(ref);
					//can't upgrade readLock, so release it
					releaseIfEnsured(ref);
					tryWriteLock(ref);
					locked.add(ref);
					if(wasEnsured && ref.tvals != null && ref.tvals.point > readPoint)
						throw retryex;

					Info refinfo = ref.tinfo;
					if(refinfo != null && refinfo != info && refinfo.running())
						{
						if(!barge(refinfo))
							throw retryex;
						}
					Object val = ref.tvals == null ? null : ref.tvals.val;
					vals.put(ref, val);
					for(CFn f : e.getValue())
						{//重新执行所有的 commute
						vals.put(ref, f.fn.applyTo(RT.cons(vals.get(ref), f.args)));
						}
					}
				for(Ref ref : sets)
					{
					tryWriteLock(ref);
					locked.add(ref);
					}

				//validate and enqueue notifications
				for(Map.Entry<Ref, Object> e : vals.entrySet())
					{
					Ref ref = e.getKey();
					ref.validate(ref.getValidator(), e.getValue());
					}

				//at this point, all values calced, all refs to be written locked
				//no more client code to be called
				long commitPoint = getCommitPoint();//提交点
				for(Map.Entry<Ref, Object> e : vals.entrySet())
					{
					Ref ref = e.getKey();
					Object oldval = ref.tvals == null ? null : ref.tvals.val;
					Object newval = e.getValue();
					int hcount = ref.histCount();

					if(ref.tvals == null)
						{
						ref.tvals = new Ref.TVal(newval, commitPoint);
						}
					else if((ref.faults.get() > 0 && hcount < ref.maxHistory)
							|| hcount < ref.minHistory)
						{
						ref.tvals = new Ref.TVal(newval, commitPoint, ref.tvals);
						ref.faults.set(0);
						}
					else
						{
						ref.tvals = ref.tvals.next;
						ref.tvals.val = newval;
						ref.tvals.point = commitPoint;
						}
					if(ref.getWatches().count() > 0)
						notify.add(new Notify(ref, oldval, newval));
					}

				done = true;
				info.status.set(COMMITTED);
				}
			}
		catch(RetryEx retry)
			{
			//eat this so we retry rather than fall out
			}
		finally
			{
			for(int k = locked.size() - 1; k >= 0; --k)
				{
				locked.get(k).lock.writeLock().unlock();
				}
			locked.clear();
			for(Ref r : ensures)
				{
				r.lock.readLock().unlock();
				}
			ensures.clear();
			stop(done ? COMMITTED : RETRY);
			try
				{
				if(done) //re-dispatch out of transaction
					{
					for(Notify n : notify)
						{
						n.ref.notifyWatches(n.oldval, n.newval);//运行监视器
						}
					for(Agent.Action action : actions)
						{
						Agent.dispatchAction(action);//执行agent动作
						}
					}
				}
			finally
				{
				notify.clear();
				actions.clear();
				}
			}
		}
	if(!done)
		throw Util.runtimeException("Transaction failed after reaching retry limit");
	return ret;
}

public void enqueue(Agent.Action action){
	actions.add(action);
}

Object doGet(Ref ref){
	if(!info.running())
		throw retryex;
	if(vals.containsKey(ref))
		return vals.get(ref);
	try
		{
		ref.lock.readLock().lock();
		if(ref.tvals == null)
			throw new IllegalStateException(ref.toString() + " is unbound.");
		Ref.TVal ver = ref.tvals;
		do
			{
			if(ver.point <= readPoint)
				return ver.val;
			} while((ver = ver.prior) != ref.tvals);
		}
	finally
		{
		ref.lock.readLock().unlock();
		}
	//no version of val precedes the read point
	ref.faults.incrementAndGet();//读失败，递增“读失败”次数
	throw retryex;

}

Object doSet(Ref ref, Object val){
	if(!info.running())
		throw retryex;
	if(commutes.containsKey(ref))//不能在 commute 之后设置 ref 的值
		throw new IllegalStateException("Can't set after commute");
	if(!sets.contains(ref))
		{
		sets.add(ref);//记录所有用到的 ref
		lock(ref);
		}
	vals.put(ref, val);//记录所有被更新的 ref
	return val;
}

void doEnsure(Ref ref){
	if(!info.running())
		throw retryex;
	if(ensures.contains(ref))
		return;
	ref.lock.readLock().lock();

	//someone completed a write after our snapshot
	//有别的事务已经更新 ref，所以本事务得重试
	if(ref.tvals != null && ref.tvals.point > readPoint) {
        ref.lock.readLock().unlock();
        throw retryex;
    }

	Info refinfo = ref.tinfo;

	//writer exists
	if(refinfo != null && refinfo.running())
		{
		ref.lock.readLock().unlock();

		if(refinfo != info) //not us, ensure is doomed
			{
			blockAndBail(refinfo); 
			}
		}
	else
		ensures.add(ref);//记录被 ensure 的 ref
}

Object doCommute(Ref ref, IFn fn, ISeq args) {
	if(!info.running())
		throw retryex;
	if(!vals.containsKey(ref))
		{
		Object val = null;
		try
			{
			ref.lock.readLock().lock();
			val = ref.tvals == null ? null : ref.tvals.val;
			}
		finally
			{
			ref.lock.readLock().unlock();
			}
		vals.put(ref, val);
		}
	ArrayList<CFn> fns = commutes.get(ref);
	if(fns == null)
		commutes.put(ref, fns = new ArrayList<CFn>());
	fns.add(new CFn(fn, args));//记录被 commute 的 ref
	Object ret = fn.applyTo(RT.cons(vals.get(ref), args));
	vals.put(ref, ret);
	return ret;
}

/*
//for test
static CyclicBarrier barrier;
static ArrayList<Ref> items;

public static void main(String[] args){
	try
		{
		if(args.length != 4)
			System.err.println("Usage: LockingTransaction nthreads nitems niters ninstances");
		int nthreads = Integer.parseInt(args[0]);
		int nitems = Integer.parseInt(args[1]);
		int niters = Integer.parseInt(args[2]);
		int ninstances = Integer.parseInt(args[3]);

		if(items == null)
			{
			ArrayList<Ref> temp = new ArrayList(nitems);
			for(int i = 0; i < nitems; i++)
				temp.add(new Ref(0));
			items = temp;
			}

		class Incr extends AFn{
			public Object invoke(Object arg1) {
				Integer i = (Integer) arg1;
				return i + 1;
			}

			public Obj withMeta(IPersistentMap meta){
				throw new UnsupportedOperationException();

			}
		}

		class Commuter extends AFn implements Callable{
			int niters;
			List<Ref> items;
			Incr incr;


			public Commuter(int niters, List<Ref> items){
				this.niters = niters;
				this.items = items;
				this.incr = new Incr();
			}

			public Object call() {
				long nanos = 0;
				for(int i = 0; i < niters; i++)
					{
					long start = System.nanoTime();
					LockingTransaction.runInTransaction(this);
					nanos += System.nanoTime() - start;
					}
				return nanos;
			}

			public Object invoke() {
				for(Ref tref : items)
					{
					LockingTransaction.getEx().doCommute(tref, incr);
					}
				return null;
			}

			public Obj withMeta(IPersistentMap meta){
				throw new UnsupportedOperationException();

			}
		}

		class Incrementer extends AFn implements Callable{
			int niters;
			List<Ref> items;


			public Incrementer(int niters, List<Ref> items){
				this.niters = niters;
				this.items = items;
			}

			public Object call() {
				long nanos = 0;
				for(int i = 0; i < niters; i++)
					{
					long start = System.nanoTime();
					LockingTransaction.runInTransaction(this);
					nanos += System.nanoTime() - start;
					}
				return nanos;
			}

			public Object invoke() {
				for(Ref tref : items)
					{
					//Transaction.get().doTouch(tref);
//					LockingTransaction t = LockingTransaction.getEx();
//					int val = (Integer) t.doGet(tref);
//					t.doSet(tref, val + 1);
					int val = (Integer) tref.get();
					tref.set(val + 1);
					}
				return null;
			}

			public Obj withMeta(IPersistentMap meta){
				throw new UnsupportedOperationException();

			}
		}

		ArrayList<Callable<Long>> tasks = new ArrayList(nthreads);
		for(int i = 0; i < nthreads; i++)
			{
			ArrayList<Ref> si;
			synchronized(items)
				{
				si = (ArrayList<Ref>) items.clone();
				}
			Collections.shuffle(si);
			tasks.add(new Incrementer(niters, si));
			//tasks.add(new Commuter(niters, si));
			}
		ExecutorService e = Executors.newFixedThreadPool(nthreads);

		if(barrier == null)
			barrier = new CyclicBarrier(ninstances);
		System.out.println("waiting for other instances...");
		barrier.await();
		System.out.println("starting");
		long start = System.nanoTime();
		List<Future<Long>> results = e.invokeAll(tasks);
		long estimatedTime = System.nanoTime() - start;
		System.out.printf("nthreads: %d, nitems: %d, niters: %d, time: %d%n", nthreads, nitems, niters,
		                  estimatedTime / 1000000);
		e.shutdown();
		for(Future<Long> result : results)
			{
			System.out.printf("%d, ", result.get() / 1000000);
			}
		System.out.println();
		System.out.println("waiting for other instances...");
		barrier.await();
		synchronized(items)
			{
			for(Ref item : items)
				{
				System.out.printf("%d, ", (Integer) item.currentVal());
				}
			}
		System.out.println("\ndone");
		System.out.flush();
		}
	catch(Exception ex)
		{
		ex.printStackTrace();
		}
}
*/
}
