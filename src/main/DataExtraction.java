package com.regulatory.framework.extraction;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.regulatory.framework.core.ConfigurationManager;
import com.regulatory.framework.core.DataSet;
import com.regulatory.framework.core.WorkflowContext;

/**
 * Module responsible for extracting data from various data sources.
 */
public class DataExtractionModule {
    private static final Logger logger = LoggerFactory.getLogger(DataExtractionModule.class);
    
    private final ConfigurationManager configManager;
    private final Map<String, DataSource> dataSources;
    
    public DataExtractionModule(
