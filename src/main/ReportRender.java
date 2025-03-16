package com.regulatory.framework.rendering;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.regulatory.framework.core.ConfigurationManager;
import com.regulatory.framework.core.DataRow;
import com.regulatory.framework.core.DataSet;
import com.regulatory.framework.core.ReportConfig;
import com.regulatory.framework.core.WorkflowContext;

/**
 * Renderer for generating reports in various formats.
 */
public class ReportRenderer {
    private static final Logger logger = LoggerFactory.getLogger(ReportRenderer.class);
    
    private final ConfigurationManager configManager;
    private final Map<String, ReportTemplate> templateCache;
    
    public ReportRenderer(ConfigurationManager configManager) {
        this.configManager = configManager;
        this.templateCache = new HashMap<>();
    }
    
    /**
     * Renders a report based on the provided data and report configuration.
     * 
     * @param dataSet The dataset to render
     * @param reportConfig The report configuration containing template information
     * @param context The workflow context
     * @return The path to the generated report
     */
    public String renderReport(DataSet dataSet, ReportConfig reportConfig, WorkflowContext context) {
        logger.info("Rendering report for: {}", reportConfig.getReportId());
        
        ReportTemplate template = getOrLoadTemplate(reportConfig.getReportTemplate());
        String outputFormat = reportConfig.getOutputFormat();
        
        switch (outputFormat.toLowerCase()) {
            case "excel":
                return renderExcelReport(dataSet, template, reportConfig, context);
            case "xml":
                return renderXmlReport(dataSet, template, reportConfig, context);
            case "pdf":
                return renderPdfReport(dataSet, template, reportConfig, context);
            default:
                throw new UnsupportedOperationException("Unsupported output format: " + outputFormat);
        }
    }
    
    /**
     * Renders a report in Excel format.
     */
    private String renderExcelReport(DataSet dataSet, ReportTemplate template, ReportConfig reportConfig, WorkflowContext context) {
        String templatePath = template.getTemplatePath();
        String outputPath = generateOutputPath(reportConfig, context, "xlsx");
        
        try (Workbook workbook = WorkbookFactory.create(new File(templatePath))) {
            processExcelTemplate(workbook, dataSet, template);
            
            // Save the workbook
            try (FileOutputStream out = new FileOutputStream(outputPath)) {
                workbook.write(out);
            }
            
            return outputPath;
        } catch (IOException e) {
            logger.error("Error rendering Excel report: {}", e.getMessage(), e);
            throw new ReportRenderingException("Failed to render Excel report", e);
        }
    }
    
    /**
     * Processes an Excel template.
     */
    private void processExcelTemplate(Workbook workbook, DataSet dataSet, ReportTemplate template) {
        TemplateType templateType = template.getType();
        
        switch (templateType) {
            case STATIC:
                processStaticExcelTemplate(workbook, dataSet, template);
                break;
            case DYNAMIC:
                processDynamicExcelTemplate(workbook, dataSet, template);
                break;
            default:
                throw new UnsupportedOperationException("Unsupported template type: " + templateType);
        }
    }
    
    /**
     * Processes a static Excel template.
     */
    private void processStaticExcelTemplate(Workbook workbook, DataSet dataSet, ReportTemplate template) {
        Map<String, String> cellMappings = template.getCellMappings();
        
        // For static templates, we expect a single row in the dataset
        if (dataSet.getRows().size() != 1) {
            logger.warn("Expected single row in dataset for static template, found: {}", dataSet.getRows().size());
        }
        
        // Use the first row of data
        DataRow dataRow = dataSet.getRows().get(0);
        
        // Process each cell mapping
        for (Map.Entry<String, String> mapping : cellMappings.entrySet()) {
            String cellReference = mapping.getKey();
            String columnName = mapping.getValue();
            
            // Get the value from the data row
            Object value = dataRow.getValue(columnName);
            
            // Set the value in the cell
            setCellValue(workbook, cellReference, value);
        }
    }
    
    /**
     * Processes a dynamic Excel template.
     */
    private void processDynamicExcelTemplate(Workbook workbook, DataSet dataSet, ReportTemplate template) {
        String sheetName = template.getDynamicSheetName();
        int startRow = template.getDynamicStartRow();
        Map
