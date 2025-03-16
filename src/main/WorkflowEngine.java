package com.regulatory.framework.workflow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.regulatory.framework.core.ConfigurationManager;
import com.regulatory.framework.core.WorkflowContext;
import com.regulatory.framework.core.WorkflowDefinition;
import com.regulatory.framework.core.WorkflowStep;
import com.regulatory.framework.core.WorkflowTask;

/**
 * Engine for orchestrating the end-to-end report generation workflow.
 */
public class WorkflowEngine {
    private static final Logger logger = LoggerFactory.getLogger(WorkflowEngine.class);
    
    private final ConfigurationManager configManager;
    private final ExecutorService executorService;
    private final Map<String, WorkflowExecutor> executors;
    
    public WorkflowEngine(ConfigurationManager configManager, int threadPoolSize) {
        this.configManager = configManager;
        this.executorService = Executors.newFixedThreadPool(threadPoolSize);
        this.executors = registerExecutors();
    }
    
    /**
     * Executes a workflow based on its definition.
     * 
     * @param workflowId The ID of the workflow to execute
     * @param parameters The input parameters for the workflow
     * @return WorkflowContext containing the execution results
     */
    public WorkflowContext executeWorkflow(String workflowId, Map<String, Object> parameters) {
        logger.info("Starting workflow execution: {}", workflowId);
        
        // Load workflow definition
        WorkflowDefinition workflow = configManager.getWorkflowDefinition(workflowId);
        if (workflow == null) {
            throw new WorkflowException("Workflow definition not found: " + workflowId);
        }
        
        // Create workflow context
        WorkflowContext context = new WorkflowContext();
        context.setRunId(generateRunId());
        context.setWorkflowId(workflowId);
        context.setParameters(parameters != null ? parameters : new HashMap<>());
        context.setResults(new HashMap<>());
        
        try {
            // Build dependency graph
            DirectedAcyclicGraph<String> dag = buildDependencyGraph(workflow);
            
            // Execute workflow
            executeWorkflowSteps(workflow, dag, context);
            
            logger.info("Workflow execution completed successfully: {}", workflowId);
            return context;
        } catch (Exception e) {
            logger.error("Workflow execution failed: {}", e.getMessage(), e);
            context.setError(e);
            throw new WorkflowException("Workflow execution failed", e);
        }
    }
    
    /**
     * Executes the workflow steps based on the dependency graph.
     */
    private void executeWorkflowSteps(WorkflowDefinition workflow, DirectedAcyclicGraph<String> dag, WorkflowContext context) {
        // Process steps in topological order
        List<Set<String>> levels = dag.getLevels();
        
        for (Set<String> level : levels) {
            // Execute steps at this level in parallel
            List<CompletableFuture<Void>> futures = level.stream()
                .map(stepId -> CompletableFuture.runAsync(() -> executeStep(workflow, stepId, context), executorService))
                .collect(Collectors.toList());
            
            // Wait for all steps at this level to complete
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            
            // Check for errors
            if (context.getError() != null) {
                throw new WorkflowException("Step execution failed", context.getError());
            }
        }
    }
    
    /**
     * Executes a single workflow step.
     */
    private void executeStep(WorkflowDefinition workflow, String stepId, WorkflowContext context) {
        WorkflowStep step = workflow.getStep(stepId);
        if (step == null) {
            throw new WorkflowException("Step not found: " + stepId);
        }
        
        logger.info("Executing step: {}", stepId);
        
        try {
            // Get the executor for this step type
            WorkflowExecutor executor = executors.get(step.getType());
            if (executor == null) {
                throw new WorkflowException("No executor found for step type: " + step.getType());
            }
            
            // Create a task for the executor
            WorkflowTask task = new WorkflowTask();
            task.setStepId(stepId);
            task.setStepConfig(step.getConfig());
            task.setContext(context);
            
            // Execute the task
            executor.execute(task);
            
            logger.info("Step completed successfully: {}", stepId);
        } catch (Exception e) {
            logger.error("Step execution failed: {} - {}", stepId, e.getMessage(), e);
            synchronized (context) {
                context.setError(e);
            }
            throw new WorkflowException("Step execution failed: " + stepId, e);
        }
    }
    
    /**
     * Builds a directed acyclic graph (DAG) of workflow steps based on dependencies.
     */
    private DirectedAcyclicGraph<String> buildDependencyGraph(WorkflowDefinition workflow) {
        DirectedAcyclicGraph<String> dag = new DirectedAcyclicGraph<>();
        
        // Add all steps to the graph
        for (WorkflowStep step : workflow.getSteps()) {
            dag.addNode(step.getId());
        }
        
        // Add dependencies
        for (WorkflowStep step : workflow.getSteps()) {
            if (step.getDependencies() != null) {
                for (String dependency : step.getDependencies()) {
                    dag.addEdge(dependency, step.getId());
                }
            }
        }
        
        // Check for cycles
        if (dag.hasCycle()) {
            throw new WorkflowException("Workflow definition contains cycles");
        }
        
        return dag;
    }
    
    /**
     * Registers workflow executors.
     */
    private Map<String, WorkflowExecutor> registerExecutors() {
        Map<String, WorkflowExecutor> map = new HashMap<>();
        
        // Register standard executors
        map.put("sql-extraction", new SqlExtractionExecutor());
        map.put("validation", new ValidationExecutor());
        map.put("calculation", new CalculationExecutor());
        map.put("aggregation", new AggregationExecutor());
        map.put("reporting", new ReportingExecutor());
        
        return map;
    }
    
    /**
     * Generates a unique run ID.
     */
    private String generateRunId() {
        return UUID.randomUUID().toString();
    }
    
    /**
     * Shuts down the executor service.
     */
    public void shutdown() {
        executorService.shutdown();
    }
    
    /**
     * Directed Acyclic Graph implementation for dependency management.
     */
    private static class DirectedAcyclicGraph<T> {
        private Map<T, Set<T>> outgoingEdges;
        private Map<T, Set<T>> incomingEdges;
        
        public DirectedAcyclicGraph() {
            outgoingEdges = new HashMap<>();
            incomingEdges = new HashMap<>();
        }
        
        public void addNode(T node) {
            outgoingEdges.putIfAbsent(node, new HashSet<>());
            incomingEdges.putIfAbsent(node, new HashSet<>());
        }
        
        public void addEdge(T from, T to) {
            addNode(from);
            addNode(to);
            outgoingEdges.get(from).add(to);
            incomingEdges.get(to).add(from);
        }
        
        public boolean hasCycle() {
            Set<T> visited = new HashSet<>();
            Set<T> recursionStack = new HashSet<>();
            
            for (T node : outgoingEdges.keySet()) {
                if (hasCycleDFS(node, visited, recursionStack)) {
                    return true;
                }
            }
            
            return false;
        }
        
        private boolean hasCycleDFS(T node, Set<T> visited, Set<T> recursionStack) {
            if (recursionStack.contains(node)) {
                return true;
            }
            
            if (visited.contains(node)) {
                return false;
            }
            
            visited.add(node);
            recursionStack.add(node);
            
            for (T neighbor : outgoingEdges.get(node)) {
                if (hasCycleDFS(neighbor, visited, recursionStack)) {
                    return true;
                }
            }
            
            recursionStack.remove(node);
            return false;
        }
        
        public List<Set<T>> getLevels() {
            List<Set<T>> levels = new ArrayList<>();
            Map<T, Integer> nodeToLevelMap = new HashMap<>();
            
            // Initialize with nodes having no dependencies
            Set<T> currentLevel = incomingEdges.entrySet().stream()
                .filter(entry -> entry.getValue().isEmpty())
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
            
            int levelIndex = 0;
            while (!currentLevel.isEmpty()) {
                levels.add(currentLevel);
                
                // Mark current level nodes
                for (T node : currentLevel) {
                    nodeToLevelMap.put(node, levelIndex);
                }
                
                // Find nodes for next level
                Set<T> nextLevel = new HashSet<>();
                for (T node : currentLevel) {
                    for (T dependent : outgoingEdges.get(node)) {
                        // Check if all dependencies of this node are at previous levels
                        boolean allDependenciesProcessed = incomingEdges.get(dependent).stream()
                            .allMatch(dep -> nodeToLevelMap.containsKey(dep));
                        
                        if (allDependenciesProcessed) {
                            nextLevel.add(dependent);
                        }
                    }
                }
                
                currentLevel = nextLevel;
                levelIndex++;
            }
            
            return levels;
        }
    }
}

/**
 * Interface for workflow executors.
 */
interface WorkflowExecutor {
    void execute(WorkflowTask task);
}

/**
 * Exception for workflow execution failures.
 */
class WorkflowException extends RuntimeException {
    public WorkflowException(String message) {
        super(message);
    }
    
    public WorkflowException(String message, Throwable cause) {
        super(message, cause);
    }
}
