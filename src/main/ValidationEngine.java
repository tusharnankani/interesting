package com.regulatory.framework.validation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.drools.core.event.DefaultAgendaEventListener;
import org.kie.api.KieServices;
import org.kie.api.builder.KieBuilder;
import org.kie.api.builder.KieFileSystem;
import org.kie.api.builder.KieRepository;
import org.kie.api.builder.Message;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;
import org.kie.api.runtime.rule.FactHandle;
import org.kie.internal.io.ResourceFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.regulatory.framework.core.ConfigurationManager;
import com.regulatory.framework.core.DataSet;
import com.regulatory.framework.core.DataRow;
import com.regulatory.framework.core.ReportConfig;

/**
 * Engine for validating data against business rules.
 */
public class ValidationEngine {
    private static final Logger logger = LoggerFactory.getLogger(ValidationEngine.class);
    
    private final ConfigurationManager configManager;
    private final Map<String, KieContainer> kieContainers;
    
    public ValidationEngine(ConfigurationManager configManager) {
        this.configManager = configManager;
        this.kieContainers = new HashMap<>();
    }
    
    /**
     * Validates a dataset against the rules defined for a report.
     * 
     * @param dataSet The dataset to validate
     * @param reportConfig The report configuration containing validation rules
     * @return ValidationResult containing validated data and validation summary
     */
    public ValidationResult validate(DataSet dataSet, ReportConfig reportConfig) {
        logger.info("Validating data for report: {}", reportConfig.getReportId());
        
        List<ValidationRule> validationRules = reportConfig.getValidationRules();
        ValidationSummary validationSummary = new ValidationSummary();
        
        // Get or create KieContainer for this report
        KieContainer kieContainer = getOrCreateKieContainer(reportConfig);
        
        // Create KieSession
        KieSession kieSession = kieContainer.newKieSession();
        
        try {
            // Register ValidationContext as global
            ValidationContext validationContext = new ValidationContext();
            kieSession.setGlobal("validationContext", validationContext);
            
            // Insert data rows into session
            List<FactHandle> factHandles = new ArrayList<>();
            for (DataRow row : dataSet.getRows()) {
                factHandles.add(kieSession.insert(row));
            }
            
            // Fire all rules
            int firedRules = kieSession.fireAllRules();
            logger.debug("Fired {} rules during validation", firedRules);
            
            // Process validation results
            DataSet validatedDataSet = filterValidatedData(dataSet, validationContext);
            validationSummary = createValidationSummary(validationContext);
            
            return new ValidationResult(validatedDataSet, validationSummary);
        } finally {
            kieSession.dispose();
        }
    }
    
    /**
     * Filters data based on validation results.
     */
    private DataSet filterValidatedData(DataSet dataSet, ValidationContext validationContext) {
        DataSet validatedDataSet = dataSet.clone();
        List<DataRow> invalidRows = new ArrayList<>();
        
        for (DataRow row : validatedDataSet.getRows()) {
            String rowId = row.getRowId();
            
            // Check if row has validation errors
            if (validationContext.hasErrors(rowId)) {
                invalidRows.add(row);
            }
        }
        
        // Remove invalid rows
        for (DataRow invalidRow : invalidRows) {
            validatedDataSet.removeRow(invalidRow);
        }
        
        return validatedDataSet;
    }
    
    /**
     * Creates a validation summary from the validation context.
     */
    private ValidationSummary createValidationSummary(ValidationContext validationContext) {
        ValidationSummary summary = new ValidationSummary();
        
        summary.setTotalRows(validationContext.getTotalRows());
        summary.setValidRows(validationContext.getValidRows());
        summary.setInvalidRows(validationContext.getInvalidRows());
        summary.setValidationErrors(validationContext.getAllErrors());
        
        return summary;
    }
    
    /**
     * Gets or creates a KieContainer for a report.
     */
    private KieContainer getOrCreateKieContainer(ReportConfig reportConfig) {
        String reportId = reportConfig.getReportId();
        
        // Return existing container if available
        if (kieContainers.containsKey(reportId)) {
            return kieContainers.get(reportId);
        }
        
        // Create new container
        KieServices kieServices = KieServices.Factory.get();
        KieRepository kieRepository = kieServices.getRepository();
        KieFileSystem kieFileSystem = kieServices.newKieFileSystem();
        
        // Add validation rules to the file system
        for (ValidationRule rule : reportConfig.getValidationRules()) {
            String drlContent = rule.getDrlContent();
            String drlPath = "src/main/resources/rules/" + reportId + "/" + rule.getRuleId() + ".drl";
            kieFileSystem.write(drlPath, drlContent);
        }
        
        // Build the KieModule
        KieBuilder kieBuilder = kieServices.newKieBuilder(kieFileSystem);
        kieBuilder.buildAll();
        
        // Check for errors
        if (kieBuilder.getResults().hasMessages(Message.Level.ERROR)) {
            throw new ValidationEngineException("Error building KieContainer for report: " + reportId + 
                    " - " + kieBuilder.getResults().getMessages());
        }
        
        // Create and store the container
        KieContainer kieContainer = kieServices.newKieContainer(kieRepository.getDefaultReleaseId());
        kieContainers.put(reportId, kieContainer);
        
        return kieContainer;
    }
    
    /**
     * Closes all KieContainers.
     */
    public void close() {
        kieContainers.clear();
    }
}

/**
 * Context for collecting validation results.
 */
class ValidationContext {
    private final Map<String, List<String>> validationErrors = new HashMap<>();
    private int totalRows = 0;
    
    /**
     * Adds a validation error for a row.
     */
    public void addError(String rowId, String errorMessage) {
        if (!validationErrors.containsKey(rowId)) {
            validationErrors.put(rowId, new ArrayList<>());
        }
        validationErrors.get(rowId).add(errorMessage);
    }
    
    /**
     * Checks if a row has validation errors.
     */
    public boolean hasErrors(String rowId) {
        return validationErrors.containsKey(rowId) && !validationErrors.get(rowId).isEmpty();
    }
    
    /**
     * Gets validation errors for a row.
     */
    public List<String> getErrors(String rowId) {
        return validationErrors.getOrDefault(rowId, new ArrayList<>());
    }
    
    /**
     * Gets all validation errors.
     */
    public Map<String, List<String>> getAllErrors() {
        return new HashMap<>(validationErrors);
    }
    
    /**
     * Increments the total row count.
     */
    public void incrementTotalRows() {
        totalRows++;
    }
    
    /**
     * Gets the total number of rows.
     */
    public int getTotalRows() {
        return totalRows;
    }
    
    /**
     * Gets the number of valid rows.
     */
    public int getValidRows() {
        return totalRows - getInvalidRows();
    }
    
    /**
     * Gets the number of invalid rows.
     */
    public int getInvalidRows() {
        return validationErrors.size();
    }
}

/**
 * Result of validation.
 */
class ValidationResult {
    private final DataSet validatedData;
    private final ValidationSummary validationSummary;
    
    public ValidationResult(DataSet validatedData, ValidationSummary validationSummary) {
        this.validatedData = validatedData;
        this.validationSummary = validationSummary;
    }
    
    public DataSet getValidatedData() {
        return validatedData;
    }
    
    public ValidationSummary getValidationSummary() {
        return validationSummary;
    }
}

/**
 * Summary of validation results.
 */
class ValidationSummary {
    private int totalRows;
    private int validRows;
    private int invalidRows;
    private Map<String, List<String>> validationErrors;
    
    // Getters and setters
    
    public void setTotalRows(int totalRows) {
        this.totalRows = totalRows;
    }
    
    public int getTotalRows() {
        return totalRows;
    }
    
    public void setValidRows(int validRows) {
        this.validRows = validRows;
    }
    
    public int getValidRows() {
        return validRows;
    }
    
    public void setInvalidRows(int invalidRows) {
        this.invalidRows = invalidRows;
    }
    
    public int getInvalidRows() {
        return invalidRows;
    }
    
    public void setValidationErrors(Map<String, List<String>> validationErrors) {
        this.validationErrors = validationErrors;
    }
    
    public Map<String, List<String>> getValidationErrors() {
        return validationErrors;
    }
}

/**
 * Exception for validation engine failures.
 */
class ValidationEngineException extends RuntimeException {
    public ValidationEngineException(String message) {
        super(message);
    }
    
    public ValidationEngineException(String message, Throwable cause) {
        super(message, cause);
    }
}

/**
 * Represents a validation rule.
 */
class ValidationRule {
    private String ruleId;
    private String ruleName;
    private String description;
    private String drlContent;
    private RuleType ruleType;
    
    public enum RuleType {
        FIELD,
        ROW,
        CROSS_ROW
    }
    
    // Getters and setters
    
    public String getRuleId() {
        return ruleId;
    }
    
    public String getDrlContent() {
        return drlContent;
    }
}
