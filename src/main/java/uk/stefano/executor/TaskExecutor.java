package uk.stefano.executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.Exception;import java.lang.IllegalStateException;import java.lang.InterruptedException;import java.lang.Runnable;import java.lang.RuntimeException;import java.lang.Thread;
import java.util.*;
import java.util.concurrent.ExecutorService;import java.util.concurrent.Executors;import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class TaskExecutor<T> {

    private final static ExecutorService EXECUTOR_SERVICE = Executors.newCachedThreadPool();
    private final Map<T, Collection<T>> downstreamMap = new HashMap<>();
    private final Map<T, Collection<T>> upstreamMap = new HashMap<>();
    private final Map<T, Task> taskMap = new HashMap<>();
    private final static Logger LOGGER = LoggerFactory.getLogger(TaskExecutor.class);
    private final Lock globalLock = new ReentrantLock();

    private Condition waitingCondition;

    private Set<T> targets = new HashSet<>();
    private Set<T> completedTargets = new HashSet<>();
    private Set<T> uncompletedTargets = new HashSet<>();
    private Set<T> currentlyExecutingTargets = new HashSet<>();

    private RuntimeException exception;

    public synchronized void buildTaskFromDependencyInformation(T taskTarget, Collection<T> dependsOn, Runnable task) {

        LOGGER.info("Registering: " + taskTarget + " with dependsOn: " + dependsOn);
        populateDownstreamInfo(taskTarget, dependsOn);
        populateUpstreamInfo(taskTarget, dependsOn);
        taskMap.put(taskTarget, new Task(taskTarget, task));
        targets.add(taskTarget);

    }

    //TODO
    private void doFullCheck(){

        Map<T, Boolean> previouslyConnected = new HashMap<>();
        Collection<T> rootNodes = determineTargetsWithNoUpstream();

        for (T task : uncompletedTargets){

        }

    }
    //TODO
    private boolean doUpwardsConnectabilityCheck(T nodeToCheck, Map<T, Boolean> previouslyCheckedNodes, Collection<T> rootNodes){

        if (rootNodes.contains(nodeToCheck)){
            return true;
        }else{
            Collection<T> upwardsDependencies = upstreamMap.get(nodeToCheck);
            for (T dependency : upwardsDependencies){
                Boolean upStreamConnected;
                if ((upStreamConnected = previouslyCheckedNodes.containsKey(dependency)) != null){

                }
            }
        }

        //TODO
        return false;

    }

    private void verifyReachability(){
        Collection<T> rootNodes = determineTargetsWithNoUpstream();
        for (T target : uncompletedTargets){
            if (!isConnectedToRoot(target, rootNodes)){
                throw new IllegalStateException("Could not find a way to satisfy requirements of: " + target);
            }
        }
    }

    private boolean isConnectedToRoot(T target, Collection<T> roots){
        for (T root : roots){
            if (connected(target, root)){
                return true;
            }
        }
        return false;
    }

    private boolean connected(T target, T start){
        //Do a dfs

        Queue<T> nodesToVisit = new ArrayDeque<>();
        nodesToVisit.add(start);
        Set<T> visitedNodes = new HashSet<>();
        while (!nodesToVisit.isEmpty()){
            T nodeToVist = nodesToVisit.remove();
            if (nodeToVist ==  target){
                return true;
            }
            visitedNodes.add(nodeToVist);
            Collection<T> children = downstreamMap.get(nodeToVist);
            if (children == null){
                continue;
            }
            for (T child : children){
                if (!visitedNodes.contains(child)){
                    nodesToVisit.add(child);
                }
            }
        }
        return false;
    }

    private void populateUpstreamInfo(T taskTarget, Collection<T> dependsOn) {
        Collection<T> upstreamDependencies = upstreamMap.get(taskTarget);
        if (upstreamDependencies == null) {
            upstreamDependencies = new HashSet<>();
            upstreamMap.put(taskTarget, upstreamDependencies);
        }
        upstreamDependencies.addAll(dependsOn);
    }

    private void populateDownstreamInfo(T taskTarget, Collection<T> dependsOn) {
        for (T provider : dependsOn) {
            Collection<T> downstreamProvidees;
            if ((downstreamProvidees = downstreamMap.get(provider)) == null) {
                downstreamProvidees = new HashSet<>();
                downstreamMap.put(provider, downstreamProvidees);
            }
            downstreamProvidees.add(taskTarget);
        }
    }

    public void execute(boolean failFast) throws InterruptedException {
        setupGlobalState();
        if (uncompletedTargets.size() == 0){
            return;
        }
        if (failFast){
            verifyReachability();
        }
        discoverAndScheduleRootTasks();
        waitForCompletion();
        resetGlobalState();
        checkForException();
    }
    public void execute() throws InterruptedException {
        execute(true);
    }

    private void setupGlobalState() {
        uncompletedTargets.addAll(targets);
        waitingCondition = globalLock.newCondition();
    }

    private void resetGlobalState() {
        completedTargets = new HashSet<>();
        uncompletedTargets = new HashSet<>();
        currentlyExecutingTargets = new HashSet<>();
    }

    private void discoverAndScheduleRootTasks() {
        Collection<T> targetsWithNoUpstreams = determineTargetsWithNoUpstream();
        LOGGER.info("Starting targets: " + targetsWithNoUpstreams);
        for (T targetWithNoUpStream : targetsWithNoUpstreams){
            scheduleTask(targetWithNoUpStream);
        }
    }

    private void waitForCompletion() throws InterruptedException {
        globalLock.lock();

        try{
            waitingCondition.await();
        }finally {
            globalLock.unlock();
        }
    }

    private void checkForException(){

        RuntimeException capturedException = exception;
        if (capturedException != null){
            exception = null;
            throw capturedException;
        }
    }

    private Collection<T> determineTargetsWithNoUpstream() {
        Collection<T> returned = new HashSet<>();
        for (Map.Entry<T, Collection<T>> entry : upstreamMap.entrySet()){
            if (entry.getValue().isEmpty()){
                    returned.add(entry.getKey());
            }
        }

        return returned;
    }

    private synchronized void handleCompletionForTask(Task completedTask) {
        T id = completedTask.id;
        LOGGER.info("Completed: " + id);
        updateTargetState(id);
        checkForCompletion();
        discoverAndScheduleDownstreamDependencies(id);
    }

    private void discoverAndScheduleDownstreamDependencies(T id) {
        Collection<T> downSteamTargets = downstreamMap.get(id);

        if (downSteamTargets != null){
            for (T downStreamTarget : downstreamMap.get(id)) {
                if (isEligibleForScheduling(downStreamTarget)) {
                    scheduleTask(downStreamTarget);
                }
            }
        }
    }

    private void checkForCompletion() {
        globalLock.lock();
        try{
            if (uncompletedTargets.isEmpty()){
                waitingCondition.signalAll();
            }
        }finally{
            globalLock.unlock();
        }
    }

    private void updateTargetState(T id) {
        completedTargets.add(id);
        uncompletedTargets.remove(id);
        currentlyExecutingTargets.remove(id);
    }

    private void handleCatastrophicFailure(Task failedTask, RuntimeException exception){
        this.exception = exception;

        globalLock.lock();
        try{
            waitingCondition.signalAll();
        }finally{
            globalLock.unlock();
        }
    }

    private void scheduleTask(T target) {
        LOGGER.info("Scheduling: " + target);
        Task task = taskMap.get(target);
        currentlyExecutingTargets.add(target);
        EXECUTOR_SERVICE.submit(task);
    }

    private boolean isEligibleForScheduling(T target) {

        if (currentlyExecutingTargets.contains(target)){
            return false;
        }
        for (T upstreamDependency : upstreamMap.get(target)) {
            if (!completedTargets.contains(upstreamDependency)) {
                LOGGER.info("Failed to schedule: " + target + " because: " + upstreamDependency + " has not been executed");
                return false;
            }
        }
        return true;
    }


    public class Task implements Runnable {
        private final T id;
        private final Runnable theRunnable;

        public Task(T id, Runnable theRunnable) {
            this.id = id;
            this.theRunnable = theRunnable;
        }

        @java.lang.Override
        public void run() {
            try{
                LOGGER.info(id + " executing in thread: " + Thread.currentThread().getName());
                theRunnable.run();
                handleCompletionForTask(this);
            }catch (Exception ex){
                LOGGER.error("error", ex);
                handleCatastrophicFailure(this, new RuntimeException(ex));
            }

        }
    }


}
