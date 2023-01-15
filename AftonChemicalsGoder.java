package com.odysseylogistics.platform.glcoding.coders.aftonchemicals;

import com.odysseylogistics.common.businessmodel.document.DocumentType;
import com.odysseylogistics.platform.beaninfoservice.intf.model.beans.shipment.ShipmentInfoBean;
import com.odysseylogistics.platform.beaninfoservice.intf.model.vo.freightinvoice.FreightInvoiceVO;
import com.odysseylogistics.platform.billingservice.intf.model.billing.InvoiceVO;
import com.odysseylogistics.platform.businessmodel.gl.bo.GLCodingData;
import com.odysseylogistics.platform.businessmodel.gl.intfc.*;
import com.odysseylogistics.platform.businessmodel.gl.javacoder.JavaGLCoderException;
import com.odysseylogistics.platform.businessmodel.gl.javacoder.JavaGLCoderResult;
import com.odysseylogistics.platform.businessmodel.gl.types.MatrixColumnDataType;
import com.odysseylogistics.platform.businessmodel.gl.vo.GLCodingBuilder;
import com.odysseylogistics.platform.businessmodel.gl.vo.GLColumnBuilder;
import com.odysseylogistics.platform.businessmodel.gl.vo.GLHeaderBuilder;
import com.odysseylogistics.platform.businessmodel.gl.vo.GLRowBuilder;
import com.odysseylogistics.platform.businessmodel.gl.vo.masterdata.MatrixColumnVO;
import com.odysseylogistics.platform.glcoding.coders.common.CodingCharge;
import com.odysseylogistics.platform.glcoding.coders.common.ConditionUtils;
import com.odysseylogistics.platform.glcoding.coders.common.Utils;
import com.odysseylogistics.platform.glcoding.exec.GLCoder;
import com.odysseylogistics.platform.glcoding.exec.JarGLCoder;
import com.odysseylogistics.platform.glcoding.model.CoderProperty;
import com.odysseylogistics.platform.glcoding.model.bo.JavaGLCoderTable;
import org.apache.commons.lang.StringUtils;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFRow;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Created by IntelliJ IDEA.
 * User: pavlosilin
 * Date: 10/27/2020
 * Time: 5:53 PM
 * To change this template use File | Settings | File and Code Templates.
 */

@GLCoder(name="Afton Chemicals Coder")
public class AftonChemicalsCoder implements JarGLCoder {
    private List tables = new ArrayList();
    private List defaultTables = new ArrayList();
    private String currentTableName;

    private FreightInvoiceVO freightInvoice;
    private ShipmentInfoBean matchedShipment;
    private InvoiceVO customerInvoice;

    private StringBuilder codingLog = new StringBuilder();
    private MatrixBuilder<? extends Matrix> codingMatrixBuilder = new GLCodingBuilder();

    private boolean masterBol = false;
    private Map aftonMasterBolProps;
    private Map<String, Double> products;
    private int rowNumber = 1;
    private String defaultPlantCode;
    private String currentPlantCode;
    private String sapChargeType;

    private int allocationLineNumber = 0;
    private BigDecimal totalCodedChargeAmount;
    private GLRowBuilder lastRowBuilder;

    private MatrixRow firstRow;
    private MatrixRow baseRateRow;
    private String specialCharges = "MKU,FAH,CarSvcFee,TRK";

    Double totalWeight = new Double(0);

    @Override
    public String getCoderVersionInfo() {
        return "$Revision: #12 $ $DateTime: 2022/09/26 03:28:15 $ $File: //depot/product/nn/qa/main/root/code/platform/modules/glcoding/src/com/odysseylogistics/platform/glcoding/coders/aftonchemicals/AftonChemicalsCoder.java $";
    }

    @Override
    public String getDescription() {
        return "";
    }

    public StringBuilder getCodingLog() {
        return codingLog;
    }

    @Override
    public JavaGLCoderResult code(FreightInvoiceVO freightInvoice, ShipmentInfoBean matchedShipment, InvoiceVO customerInvoice, Map stepAttributes) throws JavaGLCoderException {
        this.freightInvoice = freightInvoice;
        this.matchedShipment = matchedShipment;
        this.customerInvoice = customerInvoice;
        List<CodingCharge> codingCharges = Utils.getCodingCharges(freightInvoice, customerInvoice, codingLog);
        tables = Utils.loadTables(freightInvoice.getOrgId());
        addHeader();

        if (matchedShipment == null) {
            codingLog.append("Matched Shipment is null\n");
            aftonMasterBolProps = new HashMap();
            products = new TreeMap();
            products.put("-", 0d);
        } else {

            if (matchedShipment.getDocType() == DocumentType.MASTER_BILL) {
                codingLog.append("Shipment is master BOL\n");
                masterBol = true;
            }

            aftonMasterBolProps = AftonHelper.loadAftonMasterBolProps(freightInvoice, matchedShipment, customerInvoice, codingLog);
            products = AftonHelper.loadShipmentProducts(matchedShipment, codingLog);
            for (Map.Entry<String, Double> entry : products.entrySet()) {
                totalWeight = totalWeight + entry.getValue();
            }

            defaultPlantCode = AftonHelper.getPlantCode(freightInvoice, aftonMasterBolProps, codingLog);
            currentPlantCode = defaultPlantCode;
        }

        codingLog.append("Afton Master Bol Props: ").append(aftonMasterBolProps).append("\n\n");

        boolean dedicatedTrailerProcessed = false;

        Matrix fiMatrix = getFIMatrix();
        for (Map.Entry<String, Double> product : products.entrySet()) {
            codingLog.append("\n- Current Product: ").append(product.getKey()).append("\n");

            for (CodingCharge codingCharge : codingCharges) {
                CodingCharge charge;
                if (products.size() > 1) {
                    charge = AftonHelper.getProductCharge(codingCharge, totalWeight, product.getValue());
                } else {
                    charge = codingCharge;
                }
                codingLog.append("\n-- Current Charge: ").append(charge.getType()).append("\n");

                if (customerInvoice != null) {
                    if (fiMatrix != null) {
                        GLRowBuilder rowBuilder = getFIChargeRow(fiMatrix, charge.getType());
                        if (rowBuilder != null) {
                            Utils.addCodeSell(rowBuilder, 9, charge.getType(), codingLog);
                            Utils.addCodeSell(rowBuilder, 10, charge.getAmount().getValue().toString(), codingLog);
                            codingMatrixBuilder.addRow(rowBuilder);
                            codingLog.append("Codes were taken from FI charge row\n");
                            rowNumber++;

                            if (ConditionUtils.equalsAnyIgnoreCase(charge.getType(), "BASE RATE,BAS")) {
                                baseRateRow = rowBuilder.createVO();
                            }
                            if (firstRow == null) {
                                firstRow = rowBuilder.createVO();
                            }
                            continue;
                        } else  {
                            codingLog.append("FI charge row not found\n");
                        }
                    }
                    if (ConditionUtils.equalsAnyIgnoreCase(charge.getType(), specialCharges)) {
                        if (baseRateRow != null || firstRow != null) {
                            GLRowBuilder rowBuilder;
                            if (baseRateRow != null) {
                                rowBuilder = new GLRowBuilder(baseRateRow);
                                codingLog.append("Codes were taken from BASE RATE row\n");
                            } else {
                                rowBuilder = new GLRowBuilder(firstRow);
                                codingLog.append("Codes were taken from first row\n");
                            }
                            rowBuilder.setRowNumber(rowNumber);
                            Utils.addCodeSell(rowBuilder, 9, charge.getType(), codingLog);
                            Utils.addCodeSell(rowBuilder, 10, charge.getAmount().getValue().toString(), codingLog);
                            codingMatrixBuilder.addRow(rowBuilder);
                            rowNumber++;
                        } else {
                            codingLog.append("WARNING: BASE RATE row not found!\n");
                        }
                        continue;
                    }
                }

                currentTableName = "Afton Special Coding";
                if (generateCode(true, "A,B,C,D", "E,F,G,H,I,J,K,L", 0, product, charge, new HashMap<String,String>(), false)) {
                    continue;
                }

                Map<String,String> codingInfo = getDefaultCodingInfo(product, charge);
                sapChargeType = "";
                if (codingInfo == null) {
                    currentTableName = "NonFrt Charges";
                    sapChargeType = getIntermediateCode(product, charge, "A", "B", 0, "Afton defaults");
                    codingLog.append("SAP Charge Type value: ").append(sapChargeType).append("\n\n");

                    currentTableName = "NonFrt Charge codes";
                    if (generateCode(true, "A,F", "H,I,J,K,L,M,N,P", 0, product, charge, new HashMap<String,String>(), false)) {
                        continue;
                    }

                    currentTableName = "FINAL CHARGE CODE LIST";
                    codingInfo = getFreightAccount(product, charge, "A", "C,E,G", 0);
                }

                if (StringUtils.equalsIgnoreCase(codingInfo.get("scenarioType"), "Dedicated Trailer Coding")) {
                    dedicatedTrailerProcessed = true;
                    currentTableName = "DEDTRL_current";
                    codingLog.append("Dedicated Trailer Scenario!\n");
                    generateCode(true, "A,B,C,D,E,F", "G,H,I,J,K,L,M,N,O,P", 0, product, charge, codingInfo, true);
                } else if (dedicatedTrailerProcessed) {
                    codingLog.append("Dedicated Trailer Scenario has been already processed\n");
                    GLRowBuilder rowBuilder = Utils.getEmptyRow(rowNumber, 10);
                    Utils.addCodeSell(rowBuilder, 9, charge.getType(), codingLog);
                    Utils.addCodeSell(rowBuilder, 10, charge.getAmount().getValue().toString(), codingLog);
                    codingMatrixBuilder.addRow(rowBuilder);
                    rowNumber++;

                } else {
                    codingLog.append("Standard Scenario!\n");
                    currentPlantCode = defaultPlantCode;

                    currentTableName = "Lookup Table";
                    String companyCode = getIntermediateCode(product, charge, "H", "I", 7, "Main");
                    codingLog.append("Company Code value: ").append(companyCode).append("\n\n");

                    currentTableName = "Afton Ship TO";
                    String shipToPlantCode = getIntermediateCode(product, charge, "A", "B", 0, "Main");
                    codingLog.append("Ship To Plant Code value: ").append(shipToPlantCode).append("\n");

                    if (StringUtils.isNotBlank(shipToPlantCode) && codingInfo.get("scenarioType") != null && "Receiving Plant Cost Center".equalsIgnoreCase(codingInfo.get("scenarioType"))) {
                        codingLog.append("Current Plant Code for Cost center search has been changed because of scenarioType: ").append(codingInfo.get("scenarioType")).append("\n");
                        currentPlantCode = shipToPlantCode;
                    }
                    codingLog.append("Current Plant Code for Cost center search: ").append(currentPlantCode).append("\n\n");

                    currentTableName = "Lookup Table";
                    String costCenter = getIntermediateCode(product, charge, "A,B", "C", 7, "Main");
                    codingLog.append("Cost Center value: ").append(costCenter).append("\n\n");

                    currentPlantCode = defaultPlantCode;

                    currentTableName = "Lookup Table";
                    String iOpO = getIntermediateCode(product, charge, "E", "F", 7, "Main");
                    codingLog.append("I0/P0 value: ").append(iOpO).append("\n\n");

                    if (StringUtils.isBlank(costCenter)) {
                        currentTableName = "Lookup Table";
                        costCenter = getIntermediateCode(product, charge, "K", "L", 7, "Main");
                        codingLog.append("INPL Cost Center value: ").append(costCenter).append("\n");
                    }

                    codingInfo.put("companyCode", companyCode);
                    codingInfo.put("costCenter", costCenter);
                    codingInfo.put("iOpO", iOpO);

                    codingLog.append("Intermediate codes: ").append(codingInfo).append("\n\n");

                    currentTableName = "Lookup Table";
                    generateCode(false, "A", "C,E,F,H,J,K,L,M", 0, product, charge, codingInfo, true);
                }

                if (customerInvoice != null) {
                    if (ConditionUtils.equalsAnyIgnoreCase(charge.getType(), "BASE RATE,BAS")) {
                        baseRateRow = lastRowBuilder.createVO();
                    }
                    if (firstRow == null) {
                        firstRow = lastRowBuilder.createVO();
                    }
                }
            }
        }

        Matrix codingData = codingMatrixBuilder.createVO();

        Utils.matrixHasEmptyCells(codingData, codingLog);

        Utils.printResultCodes(codingData, codingLog);
        return Utils.createOkResult(codingData, codingLog.toString());

    }

    private String getIntermediateCode(Map.Entry<String, Double> product, CodingCharge charge, String mainTableConditions, String mainTableActions, int skipRows, String fileName) throws JavaGLCoderException {
        codingLog.append("*Checking ").append(currentTableName).append(" table\n");
        JavaGLCoderTable mainTable =  Utils.getTableByName(tables, fileName, true);
        Workbook mainTableWb = Utils.getTableWorkbook(mainTable);
        Sheet mainTableSheet = Utils.getTableSheet(mainTableWb, currentTableName, true);
        TreeMap<Integer, String> mainTableColumns = Utils.getTableColumns(mainTableSheet,
                skipRows,
                mainTableConditions + "," + mainTableActions,
                codingLog);

        List conditionColumnsList = Utils.getWorkColumnsList(mainTableConditions);
        List actionColumnsList = Utils.getWorkColumnsList(mainTableActions);

        skipRows++;
        int rIndex = 0;
        for (Iterator rit = mainTableSheet.rowIterator(); rit.hasNext();) {
            XSSFRow row = (XSSFRow) rit.next();
            rIndex++;
            if (rIndex <= skipRows) {
                continue;
            }
            if (Utils.isRowEmpty(row)) {
                continue;
            }

            boolean conditionTrue = true;
            for (Map.Entry<Integer, String> entry : mainTableColumns.entrySet()) {
                if (!conditionColumnsList.contains(entry.getKey())) {
                    continue;
                }
                XSSFCell cell = row.getCell(entry.getKey()-1);
                conditionTrue = checkCondition(cell, entry.getValue(), product, charge);
                if (!conditionTrue) {
                    break;
                }
            }
            if (conditionTrue) {
                codingLog.append("Condition for row " + rIndex + " in table " + currentTableName + " is True\n");
                for (Map.Entry<Integer, String> entry : mainTableColumns.entrySet()) {
                    if (!actionColumnsList.contains(entry.getKey())) {
                        continue;
                    }
                    XSSFCell cell = row.getCell(entry.getKey()-1);
                    if (cell == null) {
                        return "";
                    }
                    return StringUtils.trim(Utils.getStringValue(cell));
                }
            }
        }
        codingLog.append("No codes found!!!\n");
        return "";
    }

    private Map<String,String> getDefaultCodingInfo(Map.Entry<String, Double> product, CodingCharge charge) throws JavaGLCoderException {
        currentTableName = "Main Charges";
        String freightAccount = getIntermediateCode(product, charge, "A", "B", 0, "Afton defaults");
        if (StringUtils.isBlank(freightAccount)) {
            return null;
        }
        codingLog.append("Default charge detected! Freight Account value: ").append(freightAccount).append("\n\n");

        Map<String,String> codingInfo = new HashMap<>();
        codingInfo.put("scenarioType", "");
        codingInfo.put("freightAccount", freightAccount);

        return codingInfo;
    }

    private Map<String,String> getFreightAccount(Map.Entry<String, Double> product, CodingCharge charge, String mainTableConditions, String mainTableActions, int skipRows) throws JavaGLCoderException {
        codingLog.append("*Checking ").append(currentTableName).append(" table\n");
        JavaGLCoderTable mainTable =  Utils.getTableByName(tables, "Main", true);
        Workbook mainTableWb = Utils.getTableWorkbook(mainTable);
        Sheet mainTableSheet = Utils.getTableSheet(mainTableWb, currentTableName, true);
        TreeMap<Integer, String> mainTableColumns = Utils.getTableColumns(mainTableSheet,
                skipRows,
                mainTableConditions + "," + mainTableActions,
                codingLog);

        List conditionColumnsList = Utils.getWorkColumnsList(mainTableConditions);
        List actionColumnsList = Utils.getWorkColumnsList(mainTableActions);

        Map<String,String> freightAccountInfo = new HashMap<>();
        freightAccountInfo.put("scenarioType", "");
        freightAccountInfo.put("freightAccount", "");

        int rIndex = 0;
        skipRows++;
        for (Iterator rit = mainTableSheet.rowIterator(); rit.hasNext();) {
            XSSFRow row = (XSSFRow) rit.next();
            rIndex++;
            if (rIndex <= skipRows) {
                continue;
            }
            if (Utils.isRowEmpty(row)) {
                continue;
            }

            boolean conditionTrue = true;
            for (Map.Entry<Integer, String> entry : mainTableColumns.entrySet()) {
                if (!conditionColumnsList.contains(entry.getKey())) {
                    continue;
                }
                XSSFCell cell = row.getCell(entry.getKey()-1);
                conditionTrue = checkCondition(cell, entry.getValue(), product, charge);
                if (!conditionTrue) {
                    break;
                }
            }
            if (conditionTrue) {
                codingLog.append("Condition for row " + rIndex + " in table " + currentTableName + " is True\n");

                String inboundType = "";
                String inboundValue = "";
                String outboundType = "";
                String outboundValue = "";
                String outboundAftonType = "";
                String outboundAftonValue = "";

                for (Map.Entry<Integer, String> entry : mainTableColumns.entrySet()) {
                    if (!actionColumnsList.contains(entry.getKey())) {
                        continue;
                    }
                    XSSFCell cell = row.getCell(entry.getKey()-1);
                    if (cell == null) {
                        continue;
                    }
                    XSSFCell nextCell = row.getCell(entry.getKey());
                    if (ConditionUtils.equalsAnyIgnoreCase("Inbound Shipment", entry.getValue())) {
                        inboundType = Utils.getStringValue(cell);
                        inboundValue = Utils.getStringValue(nextCell);
                    }
                    if (ConditionUtils.equalsAnyIgnoreCase("Outbound Shipment\nTO CUSTOMERS", entry.getValue())) {
                        outboundType = Utils.getStringValue(cell);
                        outboundValue = Utils.getStringValue(nextCell);
                    }
                    if (ConditionUtils.equalsAnyIgnoreCase("Outbound Shipment\nTO AFTON Ship To", entry.getValue())) {
                        outboundAftonType = Utils.getStringValue(cell);
                        outboundAftonValue = Utils.getStringValue(nextCell);
                    }

                }

                String freightAccount = "";
                String scenarioType;
                //todo

                if (ConditionUtils.equalsAnyIgnoreCase(freightInvoice.getShipDirection(),"Inbound")) {
                    freightAccount = inboundValue;
                    scenarioType = inboundType;
                    codingLog.append("Freight Account/Cost Element for Inbound shipments: ").append(freightAccount).append("\n");
                } else if (ConditionUtils.containsAnyIgnoreCase(freightInvoice.getConsigneeName(), "Afton")) {
                    freightAccount = outboundAftonValue;
                    scenarioType = outboundAftonType;
                    codingLog.append("Freight Account/Cost Element for Outbound shipments to Afton: ").append(freightAccount).append("\n");
                } else {
                    freightAccount = outboundValue;
                    scenarioType = outboundType;
                    codingLog.append("Freight Account/Cost Element for Outbound shipments to customers: ").append(freightAccount).append("\n");
                }

                freightAccountInfo.put("scenarioType", scenarioType);
                freightAccountInfo.put("freightAccount", freightAccount);

                return freightAccountInfo;
            }
        }
        codingLog.append("No codes found!!!\n");
        return freightAccountInfo;
    }

    private boolean generateCode(boolean dedicatedTrailer, String mainTableConditions, String mainTableActions, int skipRows, Map.Entry<String, Double> product, CodingCharge charge, Map<String,String> codingInfo, boolean codesRequired) throws JavaGLCoderException {
        codingLog.append("*Checking ").append(currentTableName).append(" table\n");
        JavaGLCoderTable mainTable =  Utils.getTableByName(tables, "Main", true);
        Workbook mainTableWb = Utils.getTableWorkbook(mainTable);
        Sheet mainTableSheet = Utils.getTableSheet(mainTableWb, currentTableName, true);
        TreeMap<Integer, String> mainTableColumns = Utils.getTableColumns(mainTableSheet,
                skipRows,
                mainTableConditions + "," + mainTableActions,
                codingLog);

        List conditionColumnsList = Utils.getWorkColumnsList(mainTableConditions);
        List actionColumnsList = Utils.getWorkColumnsList(mainTableActions);

        GLRowBuilder rowBuilder = Utils.getEmptyRow(rowNumber, 10);
        Utils.addCodeSell(rowBuilder, 9, charge.getType(), codingLog);

        int rIndex = 0;
        skipRows++;

        BigDecimal allocated  = new BigDecimal(0);
        BigDecimal allocation = null;
        allocationLineNumber = 0;
        totalCodedChargeAmount = null;

        for (Iterator rit = mainTableSheet.rowIterator(); rit.hasNext();) {
            XSSFRow row = (XSSFRow) rit.next();
            rIndex++;
            if (rIndex <= skipRows) {
                continue;
            }
            if (Utils.isRowEmpty(row)) {
                continue;
            }

            boolean conditionTrue = true;
            boolean conditionRowIsBlank = true;
            for (Map.Entry<Integer, String> entry : mainTableColumns.entrySet()) {
                if (!conditionColumnsList.contains(entry.getKey())) {
                    continue;
                }
                XSSFCell cell = row.getCell(entry.getKey()-1);
                if (cell != null && StringUtils.isNotBlank(Utils.getStringValue(cell))) {
                    conditionRowIsBlank = false;
                }
                conditionTrue = !dedicatedTrailer ? checkMainCondition(cell, entry.getValue(), product, charge, codingInfo) : checkCondition(cell, entry.getValue(), product, charge);
                if (!conditionTrue) {
                    break;
                }
            }

            if (conditionTrue && (!conditionRowIsBlank || allocationLineNumber >= 1)) {
                codingLog.append("Condition for row " + rIndex + " in table " + currentTableName + " is True\n");
                rowBuilder = Utils.getEmptyRow(rowNumber, 10);
                Utils.addCodeSell(rowBuilder, 9, charge.getType(), codingLog);
                for (Map.Entry<Integer, String> entry : mainTableColumns.entrySet()) {
                    if (!actionColumnsList.contains(entry.getKey())) {
                        continue;
                    }
                    XSSFCell cell = row.getCell(entry.getKey()-1);
                    if (cell == null || StringUtils.isBlank(Utils.getStringValue(cell))) {
                        continue;
                    }
                    if (ConditionUtils.equalsAnyIgnoreCase("Allocation", entry.getValue())) {
                        allocation = Utils.getBigDecimalValue(cell);
                        allocated = allocated.add(Utils.getBigDecimalValue(cell));
                        allocationLineNumber++;
                    }
                    // if first allocation or no allocations
                    //if (allocationLineNumber <= 1) {
                        addCodeCell(rowBuilder, cell, entry.getValue(), product, charge, codingInfo);
                    //}
                }

                // if no allocations
                if (allocationLineNumber < 1) {
                    Utils.addCodeSell(rowBuilder, 10, charge.getAmount().getValue().toString(), codingLog);
                    lastRowBuilder = rowBuilder;
                    codingMatrixBuilder.addRow(rowBuilder);
                    rowNumber++;
                    return true;
                }
                // if not first - get codes from last rowBuilder
//                else if (allocationLineNumber > 1) {
//                    rowBuilder = new GLRowBuilder(lastRowBuilder.createVO());
//                    rowBuilder.setRowNumber(rowNumber);
//                }

                BigDecimal lastAmount = allocation.multiply(charge.getAmount().getValue()).setScale(2, RoundingMode.HALF_UP);
                codingLog.append("Allocation #").append(allocationLineNumber).append("(").append(allocation).append("), amount: ").append(lastAmount).append("\n");
                Utils.addCodeSell(rowBuilder, 10, lastAmount.toString(), codingLog);

                if (totalCodedChargeAmount == null) {
                    totalCodedChargeAmount = new BigDecimal(0).setScale(2, RoundingMode.HALF_UP);
                }
                totalCodedChargeAmount = totalCodedChargeAmount.add(lastAmount);

                lastRowBuilder = rowBuilder;
                codingMatrixBuilder.addRow(lastRowBuilder);

                if (allocated.setScale(2, RoundingMode.HALF_UP).compareTo(new BigDecimal(1))>=0) {
                    if (lastRowBuilder != null && lastAmount != null) {
                        BigDecimal correctedAmount = Utils.getCorrectedAmount(charge.getAmount().getValue(), charge.getType(), totalCodedChargeAmount, lastAmount, codingLog);
                        Utils.addCodeSell(lastRowBuilder, 10, correctedAmount.toString(), codingLog);
                        codingMatrixBuilder.addRow(lastRowBuilder);
                    }
                    rowNumber++;
                    return true;
                }
                rowNumber++;
            }
        }
        if (totalCodedChargeAmount == null && codesRequired) {
            codingLog.append("WARNING: No codes found!\n");
            Utils.addCodeSell(rowBuilder, 10, charge.getAmount().getValue().toString(), codingLog);
            codingMatrixBuilder.addRow(rowBuilder);
        }
        return false;
    }

    private void addCodeCell(GLRowBuilder rowBuilder, XSSFCell cell, String headerName, Map.Entry<String, Double> product, CodingCharge charge, Map<String,String> codingInfo) throws JavaGLCoderException {
        String cellValue = (cell == null ? "" :  Utils.getStringValue(cell));

        String companyCode = codingInfo.get("companyCode") != null ? codingInfo.get("companyCode") : cellValue;
        String costCenter = codingInfo.get("costCenter") != null ? codingInfo.get("costCenter") : cellValue;
        String iOpO = codingInfo.get("iOpO") != null ? codingInfo.get("iOpO") : cellValue;
        String freightAccount = codingInfo.get("freightAccount") != null ? codingInfo.get("freightAccount") : cellValue;
        String scenarioType = codingInfo.get("scenarioType");

        String codeValue = "";
        try {
            if (StringUtils.equalsIgnoreCase(headerName, "Company Code (4)" ) || StringUtils.equalsIgnoreCase(headerName, "Company Code")) {
                Utils.addCodeSell(rowBuilder, 1, companyCode, codingLog);
            }
            else if (StringUtils.equalsIgnoreCase(headerName, "Freight Account/Cost Element")|| StringUtils.equalsIgnoreCase(headerName, "Freight Account/Cost Element (6)")) {
                if ("CRS for manual coding".equalsIgnoreCase(scenarioType)) {
                    codingLog.append("Manual coding. Freight Account/Cost Element should be blank!\n");
                } else if (cellValue.equalsIgnoreCase("Cost Element (6)")) {
                    codeValue = freightAccount;
                } else if (cellValue.contains("codes will be")) {
                    String[] parts = StringUtils.split(cellValue.replace("(", "").replace("(","").replace(")","").replace(" codes will be","").trim(), " ");
                    String[] charges = StringUtils.split(parts[1], "/");
                    for (String otherCharge: charges) {
                        if (otherCharge.equalsIgnoreCase(charge.getType())) {
                            codeValue = parts[2];
                        }
                    }
                    if (StringUtils.isBlank(codeValue)) {
                        codeValue = parts[0];
                    }
                } else {
                    codeValue = cellValue;
                }
                Utils.addCodeSell(rowBuilder, 2, codeValue, codingLog);
            }
            else if (StringUtils.equalsIgnoreCase(headerName, "Plant Code") || StringUtils.equalsIgnoreCase(headerName, "Plant Code (4)")) {
                if (cellValue.equalsIgnoreCase("Plant Code (4)")) {
                    codeValue = defaultPlantCode;

                } else {
                    codeValue = cellValue;
                }
                Utils.addCodeSell(rowBuilder, 3, codeValue, codingLog);
            }
            else if (StringUtils.equalsIgnoreCase(headerName, "Material Code") || StringUtils.equalsIgnoreCase(headerName, "Material Code (6)")) {
                if (cellValue.equalsIgnoreCase("Material Code (6)")) {
                    codeValue = product.getKey();
                } else {
                    codeValue = cellValue;
                }
                Utils.addCodeSell(rowBuilder, 4, codeValue, codingLog);
            }
            else if (StringUtils.equalsIgnoreCase(headerName, "Cost Center") || StringUtils.equalsIgnoreCase(headerName, "Cost Center (5)")) {
                if (cellValue.equalsIgnoreCase("Cost Center (5)")) {
                    codeValue = costCenter;
                } else {
                    codeValue = cellValue;
                }
                Utils.addCodeSell(rowBuilder, 5, codeValue, codingLog);
            }
            else if (StringUtils.equalsIgnoreCase(headerName, "I0/P0")) {
                if (cellValue.equalsIgnoreCase("I0/P0")) {
                    codeValue = iOpO;
                } else if (cellValue.contains(" for ")) {
                    String[] parts = StringUtils.split(cellValue.replace(")",""), "(");
                    String[] charges = StringUtils.split(parts[1], ",");
                    for (String otherCharge: charges) {
                        String[] otherChargeInfo = StringUtils.split(otherCharge.trim(), "for");
                        if (charge.getType().equalsIgnoreCase(otherChargeInfo[1].trim())) {
                            codeValue = otherChargeInfo[0].trim();
                        }
                    }
                    if (StringUtils.isBlank(codeValue)) {
                        if (parts[0].trim().equalsIgnoreCase("I0/P0" )) {
                            codeValue = iOpO;
                        } else {
                            codeValue = parts[0].trim();
                        }
                    }
                } else {
                    codeValue = cellValue;
                }
                Utils.addCodeSell(rowBuilder, 6, codeValue, codingLog);
            }
            else if (StringUtils.equalsIgnoreCase(headerName, "VA0000000")) {
                codeValue = cellValue;
                Utils.addCodeSell(rowBuilder, 7, codeValue, codingLog);
            }
            else if (StringUtils.equalsIgnoreCase(headerName, "Assignment Field")) {
                if (cellValue.equalsIgnoreCase("Shipment Number")) {
                    codeValue = freightInvoice.getBolNumber();
                }
                else if (cellValue.equalsIgnoreCase("IB Reference Number")) {
                    if (aftonMasterBolProps.get("DELIVERIES") == null) {
                        codingLog.append("Warning! DELIVERIES not found in aftonMasterBolProps!\n");
                        codingLog.append(String.format("Can't generate code for value %s from column %s", cellValue, headerName)).append("\n");
                        codeValue = "";
                    } else {
                        codeValue = aftonMasterBolProps.get("DELIVERIES").toString();
                    }
                }
                else {
                    codeValue = cellValue;
                }
                Utils.addCodeSell(rowBuilder, 8, codeValue, codingLog);
            }
            else if (StringUtils.equalsIgnoreCase(headerName, "Charge Code")) {
                codeValue = charge.getType();
                Utils.addCodeSell(rowBuilder, 9, codeValue, codingLog);
            }
            else if (StringUtils.equalsIgnoreCase(headerName, "Allocation")) {
                // ignore
            }
            else {
                throw new JavaGLCoderException(String.format("Unknown code column: %s", headerName));
            }
        }
        catch (Exception e) {
            throw new JavaGLCoderException(String.format("Can't generate code for value %s from column %s", cellValue, headerName), e);
        }
    }

    private boolean checkCondition(XSSFCell cell, String headerName, Map.Entry<String, Double> product, CodingCharge charge) throws JavaGLCoderException {
        if (cell == null || StringUtils.isBlank(Utils.getStringValue(cell))) {
            return true;
        }

        if (headerName.equalsIgnoreCase("Plant")) {
            return ConditionUtils.equalsAnyIgnoreCase(currentPlantCode, Utils.getStringValue(cell));
        }
        else if (headerName.equalsIgnoreCase("Material Code")) {
            if (StringUtils.equalsIgnoreCase(Utils.getStringValue(cell), "ALL")) {
                return true;
            }
            return ConditionUtils.equalsAnyIgnoreCase(product.getKey(), Utils.getStringValue(cell));
        }
        else if (headerName.equalsIgnoreCase("SAGT")) {
            return ConditionUtils.equalsAnyIgnoreCase(product.getKey(), Utils.getStringValue(cell));
        }
        else if (headerName.equalsIgnoreCase("Ship to Number")) {
            if (aftonMasterBolProps.get("DELIVERIES") == null) {
                return false;
            }
            return ConditionUtils.equalsAnyIgnoreCase(aftonMasterBolProps.get("SHIPTO_SITEID").toString(), Utils.getStringValue(cell));
        }

        if (headerName.equalsIgnoreCase("FINAL CHARGE CODE") || headerName.equalsIgnoreCase("Charge Type")) {
            return ConditionUtils.equalsAnyIgnoreCase(charge.getType(), Utils.getStringValue(cell));
        }
        else if (headerName.equalsIgnoreCase("Origin City")) {
            return ConditionUtils.containsAnyIgnoreCase(freightInvoice.getOrigin().getCity(), Utils.getStringValue(cell));
        }
        else if (headerName.equalsIgnoreCase("Origin Region")) {
            return ConditionUtils.containsAnyIgnoreCase(freightInvoice.getOrigin().getRegion(), Utils.getStringValue(cell));
        }
        if (headerName.equalsIgnoreCase("Destination city") || headerName.equalsIgnoreCase("Destination city is one of")) {
            return ConditionUtils.containsAnyIgnoreCase(freightInvoice.getDestination().getCity(), Utils.getStringValue(cell));
        }
        else if (headerName.equalsIgnoreCase("Destination region")) {
            return ConditionUtils.containsAnyIgnoreCase(freightInvoice.getDestination().getRegion(), Utils.getStringValue(cell));
        }
        else if (headerName.equalsIgnoreCase("SCAC")) {
            return ConditionUtils.equalsAnyIgnoreCase(freightInvoice.getScac(), Utils.getStringValue(cell));
        }
        else if (headerName.equalsIgnoreCase("SAP Charge Type")) {
            return ConditionUtils.equalsAnyIgnoreCase(sapChargeType, Utils.getStringValue(cell));
        }
        else {
            throw new JavaGLCoderException("Can't resolve table condition header " + headerName);
        }
    }

    private boolean checkMainCondition(XSSFCell cell, String headerName, Map.Entry<String, Double> product, CodingCharge charge, Map<String,String> codingInfo) throws JavaGLCoderException {
        if (cell == null || StringUtils.isBlank(Utils.getStringValue(cell))) {
            return true;
        }
        String cellValue = Utils.getStringValue(cell);

        String companyCode = codingInfo.get("companyCode");
        String costCenter = codingInfo.get("costCenter");
        String iOpO = codingInfo.get("iOpO");
        String scenarioType = codingInfo.get("scenarioType");
        String freightAccount = codingInfo.get("freightAccount");

        if (headerName.equalsIgnoreCase("Freight Coding Structure")) {
            if (cellValue.equalsIgnoreCase("Outbound Freight")) {
               if (!ConditionUtils.containsAnyIgnoreCase(scenarioType, "Cost Center")) {
                    return ConditionUtils.equalsAnyIgnoreCase(freightInvoice.getShipDirection(), "Outbound");
                } else {
                    return false;
                }
            } else if (cellValue.equalsIgnoreCase("Inbound Freight")) {
                if (!ConditionUtils.containsAnyIgnoreCase(scenarioType, "Cost Center")) {
                    return ConditionUtils.equalsAnyIgnoreCase(freightInvoice.getShipDirection(), "Inbound");
                } else {
                    return false;
                }
            } else if (cellValue.equalsIgnoreCase("Cost Center")) {
                return ConditionUtils.containsAnyIgnoreCase(scenarioType, "Cost Center");
            } else if (cellValue.equalsIgnoreCase("Outbound Freight for QLYC Crosby TX to Galena Park TX")) {
                return AftonHelper.isQLYCCrosbyTXtoGalenaParkTX(freightInvoice) && ConditionUtils.equalsAnyIgnoreCase(freightInvoice.getShipDirection(), "Outbound");
            } else if (cellValue.equalsIgnoreCase("Cost Center for QLYC Crosby TX to Galena Park TX")) {
                return AftonHelper.isQLYCCrosbyTXtoGalenaParkTX(freightInvoice);
            } else {
                throw new JavaGLCoderException("Can't resolve Freight Coding Structure: " + cellValue);
            }
        }
        else {
            throw new JavaGLCoderException("Can't resolve table condition header " + headerName);
        }
    }

    private Matrix getFIMatrix() throws JavaGLCoderException {
        Matrix fiMatrix = null;
        if (customerInvoice != null) {
            codingLog.append("Loading codes from FI to CI\n");
            GLCodingData codingData = null;
            GLCodingData accessor = new GLCodingData();
            try {
                codingData = accessor.getActualDocumentCoding(
                        freightInvoice.getOrgId(),
                        freightInvoice.getInvoiceId(),
                        DocumentType.FREIGHT_INVOICE
                );
            } catch (Exception e) {
                throw new JavaGLCoderException("Unable to load existing FI coding matrix!");
            }

            if (codingData == null || codingData.getVO() == null
                    || codingData.getVO().getCodingMatrix() == null
                    || codingData.getVO().getCodingMatrix().getRows() == null
                    || codingData.getVO().getCodingMatrix().getHeader() == null
                    || codingData.getVO().getCodingMatrix().getHeader().getColumns() == null) {
                codingLog.append("Warning: Loaded FI coding matrix is invalid or blank!\n");
            }

            fiMatrix = codingData.getVO().getCodingMatrix();
        }
        return fiMatrix;
    }

    private GLRowBuilder getFIChargeRow(Matrix fiMatrix, String charge) throws JavaGLCoderException {
        GLRowBuilder rowBuilder = null;
        int chargeColumnNumber = 0;

        for (MatrixColumn header: fiMatrix.getHeader().getColumns()) {
            if ("Charge Code".equalsIgnoreCase(header.getName())) {
                chargeColumnNumber = header.getColumnNumber();
                break;
            }
        }

        for (MatrixRow fiRow : fiMatrix.getRows()) {
            boolean coincided = false;
            for (MatrixCell fiCell: fiRow.getCells()) {
                if (chargeColumnNumber == fiCell.getColumnNumber() && charge.equalsIgnoreCase(fiCell.getValue())) {
                    coincided = true;
                }
            }
            if (coincided) {
                rowBuilder = Utils.getEmptyRow(rowNumber,10);

                for (MatrixColumn header: fiMatrix.getHeader().getColumns()) {
                    int newColumnNumber = getCodeColumnNumberByName(header.getName());
                    String cellValue  ="";
                    for (MatrixCell fiCell: fiRow.getCells()) {
                        if (header.getColumnNumber().equals(fiCell.getColumnNumber())) {
                            cellValue = fiCell.getValue();
                        }
                    }
                    Utils.addCodeSell(rowBuilder, newColumnNumber, cellValue, codingLog);
                }

                break;
            }
        }

        return rowBuilder;
    }

    @Override
    public List<CoderProperty> getProperties() {
        return null;
    }

    private void addHeader() {
        GLHeaderBuilder headerBuilder = new GLHeaderBuilder();
        headerBuilder.addColumn(new GLColumnBuilder(new MatrixColumnVO(1, "Company Code", MatrixColumnDataType.TEXT, 20, true, null)));
        headerBuilder.addColumn(new GLColumnBuilder(new MatrixColumnVO(2, "Freight Account/Cost Element", MatrixColumnDataType.TEXT, 20, true, null)));
        headerBuilder.addColumn(new GLColumnBuilder(new MatrixColumnVO(3, "Plant Code", MatrixColumnDataType.TEXT, 20, true, null)));
        headerBuilder.addColumn(new GLColumnBuilder(new MatrixColumnVO(4, "Material Code", MatrixColumnDataType.TEXT, 20, true, null)));
        headerBuilder.addColumn(new GLColumnBuilder(new MatrixColumnVO(5, "Cost Center", MatrixColumnDataType.TEXT, 20, true, null)));
        headerBuilder.addColumn(new GLColumnBuilder(new MatrixColumnVO(6, "I0/P0", MatrixColumnDataType.TEXT, 20, true, null)));
        headerBuilder.addColumn(new GLColumnBuilder(new MatrixColumnVO(7, "VA0000000", MatrixColumnDataType.TEXT, 20, true, null)));
        headerBuilder.addColumn(new GLColumnBuilder(new MatrixColumnVO(8, "Assignment Field", MatrixColumnDataType.TEXT, 20, true, null)));
        headerBuilder.addColumn(new GLColumnBuilder(new MatrixColumnVO(9, "Charge Code", MatrixColumnDataType.TEXT, 20, true, null)));
        headerBuilder.addColumn(new GLColumnBuilder(new MatrixColumnVO(10, "Amount", MatrixColumnDataType.TEXT, 20, true, null)));
        codingMatrixBuilder.setHeader(headerBuilder);
    }

    private int getCodeColumnNumberByName(String headerName) throws JavaGLCoderException {

        if (StringUtils.equalsIgnoreCase(headerName, "Company Code") || StringUtils.equalsIgnoreCase(headerName, "Company Code (4)")) {
            return 1;
        }
        else if (StringUtils.equalsIgnoreCase(headerName, "Freight Account/Cost Element")|| StringUtils.equalsIgnoreCase(headerName, "Freight Account/Cost Element (6)")) {
            return 2;
        }
        else if (StringUtils.equalsIgnoreCase(headerName, "Plant Code") || StringUtils.equalsIgnoreCase(headerName, "Plant Code (4)")) {
            return 3;
        }
        else if (StringUtils.equalsIgnoreCase(headerName, "Material Code") || StringUtils.equalsIgnoreCase(headerName, "Material Code (6)")) {
            return 4;
        }
        else if (StringUtils.equalsIgnoreCase(headerName, "Cost Center") || StringUtils.equalsIgnoreCase(headerName, "Cost Center (5)")) {
            return 5;
        }
        else if (StringUtils.equalsIgnoreCase(headerName, "I0/P0")) {
            return 6;
        }
        else if (StringUtils.equalsIgnoreCase(headerName, "VA0000000")) {
            return 7;
        }
        else if (StringUtils.equalsIgnoreCase(headerName, "Assignment Field") || StringUtils.equalsIgnoreCase(headerName, "Delivery/PO - SAP Assign field")) {
            return 8;
        }
        else if (StringUtils.equalsIgnoreCase(headerName, "Charge Code")) {
            return 9;
        }
        else if (StringUtils.equalsIgnoreCase(headerName, "Amount")) {
            return 10;
        }

        throw new JavaGLCoderException("Can't get Column Number for code: " + headerName);
    }
}
