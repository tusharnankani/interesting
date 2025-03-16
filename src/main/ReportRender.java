package com.regulatory.framework.rendering;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import com.itextpdf.text.BaseColor;
import com.itextpdf.text.Document;
import com.itextpdf.text.DocumentException;
import com.itextpdf.text.Element;
import com.itextpdf.text.Font;
import com.itextpdf.text.FontFactory;
import com.itextpdf.text.Paragraph;
import com.itextpdf.text.Phrase;
import com.itextpdf.text.pdf.PdfPCell;
import com.itextpdf.text.pdf.PdfPTable;
import com.itextpdf.text.pdf.PdfWriter;

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
     * Gets or loads a report template.
     */
    private ReportTemplate getOrLoadTemplate(String templateId) {
        // Return cached template if available
        if (templateCache.containsKey(templateId)) {
            return templateCache.get(templateId);
        }
        
        // Load template configuration from config manager
        ReportTemplate template = configManager.getReportTemplate(templateId);
        templateCache.put(templateId, template);
        
        return template;
    }
    
    /**
     * Renders a report in Excel format.
     */
    private String renderExcelReport(DataSet dataSet, ReportTemplate template, ReportConfig reportConfig, WorkflowContext context) {
        String templatePath = template.getTemplatePath();
        String outputPath = generateOutputPath(reportConfig, context, "xlsx");
        
        try (Workbook workbook = WorkbookFactory.create(new File(templatePath))) {
            processExcelTemplate(workbook, dataSet, template);
            
            // Create output directory if it doesn't exist
            Path outputDirectory = Paths.get(outputPath).getParent();
            if (outputDirectory != null && !Files.exists(outputDirectory)) {
                Files.createDirectories(outputDirectory);
            }
            
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
        Map<String, String> columnMappings = template.getColumnMappings();
        
        // Get the target sheet
        Sheet sheet = workbook.getSheet(sheetName);
        if (sheet == null) {
            throw new ReportRenderingException("Sheet not found: " + sheetName);
        }
        
        // Get template row to copy styles from
        Row templateRow = sheet.getRow(startRow);
        if (templateRow == null) {
            throw new ReportRenderingException("Template row not found at index: " + startRow);
        }
        
        // Remove template row if configured to do so
        if (template.isRemoveTemplateRow()) {
            sheet.shiftRows(startRow + 1, sheet.getLastRowNum(), -1);
        }
        
        // Process each row in the dataset
        int currentRowIndex = startRow;
        for (DataRow dataRow : dataSet.getRows()) {
            Row excelRow = sheet.createRow(currentRowIndex);
            
            // Process each column mapping
            for (Map.Entry<String, String> mapping : columnMappings.entrySet()) {
                String columnLetter = mapping.getKey();
                String dataColumnName = mapping.getValue();
                
                // Convert column letter to index
                int columnIndex = CellReference.convertColStringToIndex(columnLetter);
                
                // Get value from data row
                Object value = dataRow.getValue(dataColumnName);
                
                // Create cell and copy style from template row
                Cell cell = excelRow.createCell(columnIndex);
                if (templateRow.getCell(columnIndex) != null) {
                    CellStyle style = templateRow.getCell(columnIndex).getCellStyle();
                    cell.setCellStyle(style);
                }
                
                // Set cell value
                setCellValueByType(cell, value);
            }
            
            currentRowIndex++;
        }
        
        // Update any formulas in the sheet
        sheet.setForceFormulaRecalculation(true);
    }
    
    /**
     * Sets a cell value in an Excel workbook.
     */
    private void setCellValue(Workbook workbook, String cellReference, Object value) {
        // Parse cell reference
        CellReference ref = new CellReference(cellReference);
        String sheetName = ref.getSheetName();
        int rowIndex = ref.getRow();
        int colIndex = ref.getCol();
        
        // Get or create sheet
        Sheet sheet;
        if (sheetName != null) {
            sheet = workbook.getSheet(sheetName);
        } else {
            sheet = workbook.getSheetAt(0);
        }
        
        if (sheet == null) {
            throw new ReportRenderingException("Sheet not found: " + sheetName);
        }
        
        // Get or create row
        Row row = sheet.getRow(rowIndex);
        if (row == null) {
            row = sheet.createRow(rowIndex);
        }
        
        // Get or create cell
        Cell cell = row.getCell(colIndex);
        if (cell == null) {
            cell = row.createCell(colIndex);
        }
        
        // Set cell value
        setCellValueByType(cell, value);
    }
    
    /**
     * Sets a cell value based on its type.
     */
    private void setCellValueByType(Cell cell, Object value) {
        if (value == null) {
            cell.setBlank();
        } else if (value instanceof String) {
            cell.setCellValue((String) value);
        } else if (value instanceof Number) {
            cell.setCellValue(((Number) value).doubleValue());
        } else if (value instanceof Boolean) {
            cell.setCellValue((Boolean) value);
        } else if (value instanceof Date) {
            cell.setCellValue((Date) value);
        } else {
            cell.setCellValue(value.toString());
        }
    }
    
    /**
     * Renders a report in XML format.
     */
    private String renderXmlReport(DataSet dataSet, ReportTemplate template, ReportConfig reportConfig, WorkflowContext context) {
        String outputPath = generateOutputPath(reportConfig, context, "xml");
        
        try {
            // Create output directory if it doesn't exist
            Path outputDirectory = Paths.get(outputPath).getParent();
            if (outputDirectory != null && !Files.exists(outputDirectory)) {
                Files.createDirectories(outputDirectory);
            }
            
            // Create XML document
            DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
            DocumentBuilder docBuilder = docFactory.newDocumentBuilder();
            org.w3c.dom.Document doc = docBuilder.newDocument();
            
            // Create root element
            Element rootElement = doc.createElement(template.getXmlRootElementName());
            doc.appendChild(rootElement);
            
            // Add namespace attributes if defined
            Map<String, String> namespaces = template.getXmlNamespaces();
            if (namespaces != null) {
                for (Map.Entry<String, String> namespace : namespaces.entrySet()) {
                    String prefix = namespace.getKey();
                    String uri = namespace.getValue();
                    
                    String attrName = prefix.isEmpty() ? "xmlns" : "xmlns:" + prefix;
                    rootElement.setAttribute(attrName, uri);
                }
            }
            
            // Process each row in the dataset
            for (DataRow dataRow : dataSet.getRows()) {
                Element rowElement = doc.createElement(template.getXmlRowElementName());
                rootElement.appendChild(rowElement);
                
                // Process each column mapping
                Map<String, String> xmlMappings = template.getXmlMappings();
                for (Map.Entry<String, String> mapping : xmlMappings.entrySet()) {
                    String xmlElementName = mapping.getKey();
                    String dataColumnName = mapping.getValue();
                    
                    // Get value from data row
                    Object value = dataRow.getValue(dataColumnName);
                    
                    if (value != null) {
                        // Create element
                        Element element = doc.createElement(xmlElementName);
                        element.setTextContent(value.toString());
                        rowElement.appendChild(element);
                    }
                }
            }
            
            // Write XML to file
            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            Transformer transformer = transformerFactory.newTransformer();
            DOMSource source = new DOMSource(doc);
            StreamResult result = new StreamResult(new File(outputPath));
            transformer.transform(source, result);
            
            return outputPath;
        } catch (ParserConfigurationException | TransformerException e) {
            logger.error("Error rendering XML report: {}", e.getMessage(), e);
            throw new ReportRenderingException("Failed to render XML report", e);
        }
    }
    
    /**
     * Renders a report in PDF format.
     */
    private String renderPdfReport(DataSet dataSet, ReportTemplate template, ReportConfig reportConfig, WorkflowContext context) {
        String outputPath = generateOutputPath(reportConfig, context, "pdf");
        
        try {
            // Create output directory if it doesn't exist
            Path outputDirectory = Paths.get(outputPath).getParent();
            if (outputDirectory != null && !Files.exists(outputDirectory)) {
                Files.createDirectories(outputDirectory);
            }
            
            // Create PDF document
            com.itextpdf.text.Document document = new com.itextpdf.text.Document();
            PdfWriter.getInstance(document, new FileOutputStream(outputPath));
            document.open();
            
            // Add title
            Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 18);
            String title = template.getPdfTitle() != null ? template.getPdfTitle() : reportConfig.getReportId();
            Paragraph titleParagraph = new Paragraph(title, titleFont);
            titleP
