/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iotdb.session.it;

import org.apache.iotdb.isession.ISession;
import org.apache.iotdb.isession.SessionDataSet;
import org.apache.iotdb.it.env.EnvFactory;
import org.apache.iotdb.it.env.cluster.node.DataNodeWrapper;
import org.apache.iotdb.it.framework.IoTDBTestRunner;
import org.apache.iotdb.itbase.category.ClusterIT;
import org.apache.iotdb.itbase.category.LocalStandaloneIT;
import org.apache.iotdb.rpc.IoTDBConnectionException;
import org.apache.iotdb.rpc.StatementExecutionException;
import org.apache.iotdb.session.Session;
import org.apache.iotdb.session.Session.Builder;

import org.apache.tsfile.enums.TSDataType;
import org.apache.tsfile.file.metadata.enums.CompressionType;
import org.apache.tsfile.file.metadata.enums.TSEncoding;
import org.apache.tsfile.read.common.Field;
import org.apache.tsfile.read.common.RowRecord;
import org.apache.tsfile.utils.Binary;
import org.apache.tsfile.write.record.Tablet;
import org.apache.tsfile.write.schema.IMeasurementSchema;
import org.apache.tsfile.write.schema.MeasurementSchema;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

@RunWith(IoTDBTestRunner.class)
@Category({LocalStandaloneIT.class, ClusterIT.class})
public class SessionIT {

  @Before
  public void setUp() throws Exception {
    EnvFactory.getEnv().initClusterEnvironment();
  }

  @After
  public void tearDown() throws Exception {
    EnvFactory.getEnv().cleanClusterEnvironment();
  }

  @Test
  public void testInsertByStrAndSelectFailedData() {
    try (ISession session = EnvFactory.getEnv().getSessionConnection()) {
      String deviceId = "root.sg1.d1";

      session.createTimeseries(
          deviceId + ".s1", TSDataType.INT64, TSEncoding.RLE, CompressionType.UNCOMPRESSED);
      session.createTimeseries(
          deviceId + ".s2", TSDataType.INT64, TSEncoding.RLE, CompressionType.UNCOMPRESSED);
      session.createTimeseries(
          deviceId + ".s3", TSDataType.INT64, TSEncoding.RLE, CompressionType.UNCOMPRESSED);
      session.createTimeseries(
          deviceId + ".s4", TSDataType.DOUBLE, TSEncoding.RLE, CompressionType.UNCOMPRESSED);

      List<IMeasurementSchema> schemaList = new ArrayList<>();
      schemaList.add(new MeasurementSchema("s1", TSDataType.INT64, TSEncoding.RLE));
      schemaList.add(new MeasurementSchema("s2", TSDataType.DOUBLE, TSEncoding.RLE));
      schemaList.add(new MeasurementSchema("s3", TSDataType.TEXT, TSEncoding.PLAIN));
      schemaList.add(new MeasurementSchema("s4", TSDataType.INT64, TSEncoding.PLAIN));

      Tablet tablet = new Tablet("root.sg1.d1", schemaList, 10);

      for (long time = 0; time < 10; time++) {
        int row = tablet.getRowSize();
        tablet.addTimestamp(row, time);
        tablet.addValue(row, 0, time);
        tablet.addValue(row, 1, 0.1d + time);
        tablet.addValue(row, 2, "ha" + time);
        tablet.addValue(row, 3, time);
      }

      try {
        session.insertTablet(tablet);
        fail();
      } catch (StatementExecutionException e) {
        // ignore
      }

      SessionDataSet dataSet =
          session.executeQueryStatement("select s1, s2, s3, s4 from root.sg1.d1");
      int i = 0;
      while (dataSet.hasNext()) {
        RowRecord record = dataSet.next();
        assertEquals(i, record.getFields().get(0).getLongV());
        Assert.assertNull(record.getFields().get(1).getDataType());
        Assert.assertNull(record.getFields().get(2).getDataType());
        assertEquals(i, record.getFields().get(3).getDoubleV(), 0.00001);
        i++;
      }

    } catch (Exception e) {
      e.printStackTrace();
      fail(e.getMessage());
    }
  }

  @Test
  public void testInsertRecord() {
    try (ISession session = EnvFactory.getEnv().getSessionConnection()) {
      List<TSDataType> dataTypeList =
          Arrays.asList(TSDataType.DATE, TSDataType.TIMESTAMP, TSDataType.BLOB, TSDataType.STRING);
      List<String> measurements = Arrays.asList("s1", "s2", "s3", "s4");
      String deviceId = "root.db.d1";
      for (int i = 0; i < dataTypeList.size(); i++) {
        String tsPath = deviceId + "." + measurements.get(i);
        if (!session.checkTimeseriesExists(tsPath)) {
          session.createTimeseries(
              tsPath, dataTypeList.get(i), TSEncoding.PLAIN, CompressionType.SNAPPY);
        }
      }
      byte[] bytes = new byte[2];
      bytes[0] = (byte) Integer.parseInt("BA", 16);
      bytes[1] = (byte) Integer.parseInt("BE", 16);
      for (long time = 10; time < 20; time++) {
        List<Object> values = new ArrayList<>();
        values.add(LocalDate.of(2024, 1, (int) time));
        values.add(time);
        values.add(new Binary(bytes));
        values.add("" + time);
        session.insertRecord(deviceId, time, measurements, dataTypeList, values);
      }
      try (SessionDataSet dataSet = session.executeQueryStatement("select * from root.db.d1")) {
        HashSet<String> columnNames = new HashSet<>(dataSet.getColumnNames());
        assertEquals(5, columnNames.size());
        for (int i = 0; i < 4; i++) {
          Assert.assertTrue(columnNames.contains(deviceId + "." + measurements.get(i)));
        }
        dataSet.setFetchSize(1024); // default is 10000
        int row = 10;
        while (dataSet.hasNext()) {
          RowRecord record = dataSet.next();
          assertEquals(row, record.getTimestamp());
          List<Field> fields = record.getFields();
          assertEquals(4, fields.size());
          for (int i = 0; i < 4; i++) {
            switch (fields.get(i).getDataType()) {
              case DATE:
                assertEquals(LocalDate.of(2024, 1, row), fields.get(i).getDateV());
                break;
              case TIMESTAMP:
                assertEquals(row, fields.get(i).getLongV());
                break;
              case BLOB:
                Assert.assertArrayEquals(bytes, fields.get(i).getBinaryV().getValues());
                break;
              case STRING:
                assertEquals("" + row, fields.get(i).getBinaryV().toString());
                break;
              default:
                fail("Unsupported data type");
            }
            fields.get(i).getDataType();
          }
          row++;
        }
        assertEquals(20, row);
      }

    } catch (Exception e) {
      e.printStackTrace();
      fail(e.getMessage());
    }
  }

  @Test
  public void testInsertStrRecord() {
    try (ISession session = EnvFactory.getEnv().getSessionConnection()) {
      List<TSDataType> dataTypeList =
          Arrays.asList(TSDataType.DATE, TSDataType.TIMESTAMP, TSDataType.BLOB, TSDataType.STRING);
      List<String> measurements = Arrays.asList("s1", "s2", "s3", "s4");
      String deviceId = "root.db.d1";
      for (int i = 0; i < dataTypeList.size(); i++) {
        String tsPath = deviceId + "." + measurements.get(i);
        if (!session.checkTimeseriesExists(tsPath)) {
          session.createTimeseries(
              tsPath, dataTypeList.get(i), TSEncoding.PLAIN, CompressionType.SNAPPY);
        }
      }
      byte[] bytes = new byte[2];
      bytes[0] = (byte) Integer.parseInt("BA", 16);
      bytes[1] = (byte) Integer.parseInt("BE", 16);
      for (long time = 10; time < 20; time++) {
        List<String> values = new ArrayList<>();
        values.add("2024-01-" + time);
        values.add("" + time);
        values.add("X'BABE'");
        values.add("" + time);
        session.insertRecord(deviceId, time, measurements, values);
      }
      try (SessionDataSet dataSet = session.executeQueryStatement("select * from root.db.d1")) {
        HashSet<String> columnNames = new HashSet<>(dataSet.getColumnNames());
        assertEquals(5, columnNames.size());
        for (int i = 0; i < 4; i++) {
          Assert.assertTrue(columnNames.contains(deviceId + "." + measurements.get(i)));
        }
        dataSet.setFetchSize(1024); // default is 10000
        int row = 10;
        while (dataSet.hasNext()) {
          RowRecord record = dataSet.next();
          System.out.println(record);
          assertEquals(row, record.getTimestamp());
          List<Field> fields = record.getFields();
          assertEquals(4, fields.size());
          for (int i = 0; i < 4; i++) {
            switch (fields.get(i).getDataType()) {
              case DATE:
                assertEquals(LocalDate.of(2024, 1, row), fields.get(i).getDateV());
                break;
              case TIMESTAMP:
                assertEquals(row, fields.get(i).getLongV());
                break;
              case BLOB:
                Assert.assertArrayEquals(bytes, fields.get(i).getBinaryV().getValues());
                break;
              case STRING:
                assertEquals("" + row, fields.get(i).getBinaryV().toString());
                break;
              default:
                fail("Unsupported data type");
            }
            fields.get(i).getDataType();
          }
          row++;
        }
        assertEquals(20, row);
      }

    } catch (Exception e) {
      e.printStackTrace();
      fail(e.getMessage());
    }
  }

  @Test
  public void testInsertTablet() {
    try (ISession session = EnvFactory.getEnv().getSessionConnection()) {
      List<IMeasurementSchema> schemaList = new ArrayList<>();
      String deviceId = "root.db.d1";
      schemaList.add(new MeasurementSchema("s1", TSDataType.DATE));
      schemaList.add(new MeasurementSchema("s2", TSDataType.TIMESTAMP));
      schemaList.add(new MeasurementSchema("s3", TSDataType.BLOB));
      schemaList.add(new MeasurementSchema("s4", TSDataType.STRING));
      Tablet tablet = new Tablet(deviceId, schemaList, 100);
      byte[] bytes = new byte[2];
      bytes[0] = (byte) Integer.parseInt("BA", 16);
      bytes[1] = (byte) Integer.parseInt("BE", 16);

      for (long time = 10; time < 15; time++) {
        int rowIndex = tablet.getRowSize();
        tablet.addTimestamp(rowIndex, time);
        tablet.addValue(
            schemaList.get(0).getMeasurementName(), rowIndex, LocalDate.of(2024, 1, (int) time));
        tablet.addValue(schemaList.get(1).getMeasurementName(), rowIndex, time);
        tablet.addValue(schemaList.get(2).getMeasurementName(), rowIndex, new Binary(bytes));
        tablet.addValue(schemaList.get(3).getMeasurementName(), rowIndex, "" + time);
      }
      session.insertTablet(tablet);
      tablet.reset();

      for (long time = 15; time < 20; time++) {
        int rowIndex = tablet.getRowSize();
        tablet.addTimestamp(rowIndex, time);
        tablet.addValue(rowIndex, 0, LocalDate.of(2024, 1, (int) time));
        tablet.addValue(rowIndex, 1, time);
        tablet.addValue(rowIndex, 2, bytes);
        tablet.addValue(rowIndex, 3, time + "");
      }
      session.insertTablet(tablet);
      tablet.reset();
      try (SessionDataSet dataSet = session.executeQueryStatement("select * from root.db.d1")) {
        HashSet<String> columnNames = new HashSet<>(dataSet.getColumnNames());
        assertEquals(5, columnNames.size());
        for (int i = 0; i < 4; i++) {
          Assert.assertTrue(
              columnNames.contains(deviceId + "." + schemaList.get(i).getMeasurementName()));
        }
        dataSet.setFetchSize(1024); // default is 10000
        int row = 10;
        while (dataSet.hasNext()) {
          RowRecord record = dataSet.next();
          assertEquals(row, record.getTimestamp());
          List<Field> fields = record.getFields();
          assertEquals(4, fields.size());
          for (int i = 0; i < 4; i++) {
            switch (fields.get(i).getDataType()) {
              case DATE:
                assertEquals(LocalDate.of(2024, 1, row), fields.get(i).getDateV());
                break;
              case TIMESTAMP:
                assertEquals(row, fields.get(i).getLongV());
                break;
              case BLOB:
                Assert.assertArrayEquals(bytes, fields.get(i).getBinaryV().getValues());
                break;
              case STRING:
                assertEquals("" + row, fields.get(i).getBinaryV().toString());
                break;
              default:
                fail("Unsupported data type");
            }
            fields.get(i).getDataType();
          }
          row++;
        }
        assertEquals(20, row);
      }
    } catch (Exception e) {
      e.printStackTrace();
      fail(e.getMessage());
    }
  }

  @Test
  public void testSessionMisuse() throws StatementExecutionException, IoTDBConnectionException {
    final DataNodeWrapper dataNode = EnvFactory.getEnv().getDataNodeWrapperList().get(0);
    final Session session = new Builder().host(dataNode.getIp()).port(dataNode.getPort()).build();
    // operate before open
    try {
      session.executeNonQueryStatement("INSERT INTO root.db1.d1 (time, s1) VALUES (1,1)");
    } catch (IoTDBConnectionException e) {
      assertEquals("Session is not open, please invoke Session.open() first", e.getMessage());
    }

    try (SessionDataSet ignored = session.executeQueryStatement("SELECT * FROM root.**")) {
    } catch (IoTDBConnectionException e) {
      assertEquals("Session is not open, please invoke Session.open() first", e.getMessage());
    }

    try {
      session.deleteData("root.ab", 100);
    } catch (IoTDBConnectionException e) {
      assertEquals("Session is not open, please invoke Session.open() first", e.getMessage());
    }

    try {
      session.insertTablet(new Tablet("root.db1.d1", Collections.emptyList()));
    } catch (IoTDBConnectionException e) {
      assertEquals("Session is not open, please invoke Session.open() first", e.getMessage());
    }

    try {
      session.deleteDatabase("root.db");
    } catch (IoTDBConnectionException e) {
      assertEquals("Session is not open, please invoke Session.open() first", e.getMessage());
    }

    // close before open
    try {
      session.close();
    } catch (IoTDBConnectionException e) {
      assertEquals("Session is not open, please invoke Session.open() first", e.getMessage());
    }

    // operate after close
    session.open();
    session.close();

    try {
      session.executeNonQueryStatement("INSERT INTO root.db1.d1 (time, s1) VALUES (1,1)");
    } catch (IoTDBConnectionException e) {
      assertEquals("Session is not open, please invoke Session.open() first", e.getMessage());
    }

    try (SessionDataSet ignored = session.executeQueryStatement("SELECT * FROM root.**")) {
    } catch (IoTDBConnectionException e) {
      assertEquals("Session is not open, please invoke Session.open() first", e.getMessage());
    }

    try {
      session.deleteData("root.ab", 100);
    } catch (IoTDBConnectionException e) {
      assertEquals("Session is not open, please invoke Session.open() first", e.getMessage());
    }

    try {
      session.insertTablet(
          new Tablet(
              "root.db1.d1",
              Collections.singletonList(new MeasurementSchema("s1", TSDataType.INT64))));
    } catch (IoTDBConnectionException e) {
      assertEquals("Session is not open, please invoke Session.open() first", e.getMessage());
    }

    try {
      session.deleteDatabase("root.db");
    } catch (IoTDBConnectionException e) {
      assertEquals("Session is not open, please invoke Session.open() first", e.getMessage());
    }

    // double close is okay
    session.close();
  }
}
