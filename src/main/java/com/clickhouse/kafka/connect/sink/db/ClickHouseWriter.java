package com.clickhouse.kafka.connect.sink.db;

import com.clickhouse.client.*;
import com.clickhouse.kafka.connect.ClickHouseSinkConnector;
import com.clickhouse.kafka.connect.sink.ClickHouseSinkTask;
import com.clickhouse.kafka.connect.sink.data.Record;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.sink.SinkRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class ClickHouseWriter implements DBWriter{

    private static final Logger LOGGER = LoggerFactory.getLogger(ClickHouseWriter.class);

    private ClickHouseNode server = null;
    private int pingTimeOut = 100;

    @Override
    public boolean start(Map<String, String> props) {
        String hostname = props.get(ClickHouseSinkConnector.HOSTNAME);
        int port = Integer.valueOf(props.get(ClickHouseSinkConnector.PORT)).intValue();
        String database = props.get(ClickHouseSinkConnector.DATABASE);
        String username = props.get(ClickHouseSinkConnector.USERNAME);
        String password = props.get(ClickHouseSinkConnector.PASSWORD);

        LOGGER.info(String.format("hostname: [%s] port [%d] database [%s] username [%s] password [%s]", hostname, port, database, username, password));

        String url = String.format("https://%s:%d/%s", hostname, port, database);

        LOGGER.info("url: " + url);

        if (username != null && password != null) {
            LOGGER.info(String.format("Adding username [%s] password [%s]  ", username, password));
            Map<String, String> options = new HashMap<>();
            options.put("user", username);
            options.put("password", password);
            server = ClickHouseNode.of(url, options);
        } else {
            server = ClickHouseNode.of(url);
        }


        ClickHouseClient clientPing = ClickHouseClient.newInstance(ClickHouseProtocol.HTTP);

        if (clientPing.ping(server, pingTimeOut)) {
            LOGGER.info("Ping is successful.");
            return true;
        }

        LOGGER.error("Unable to ping Clickhouse server.");
        return false;
    }

    @Override
    public void stop() {

    }


    private String convertWithStream(List<Object> values, String prefixChar, String suffixChar, String delimiterChar, String trimChar) {
        return values.stream().map(v -> trimChar + v.toString() + trimChar).collect(Collectors.joining(delimiterChar, prefixChar, suffixChar));
    }

    private String extractFields(List<Field> fields, String prefixChar, String suffixChar, String delimiterChar, String trimChar) {
        return fields.stream().map(v -> trimChar + v.name() + trimChar).collect(Collectors.joining(delimiterChar, prefixChar, suffixChar));
    }

    @Override
    public void doInsert(List<Record> records) {
        long s1 = System.currentTimeMillis();

        if ( records.isEmpty() )
            return;
        int batchSize = records.size();
        LOGGER.info(String.format("Number of records to insert %d", batchSize));
        Record first = records.get(0);
        // Build the insert SQL
        StringBuffer sb = new StringBuffer();
        sb.append("INSERT INTO stock_v1 ");
        sb.append(extractFields(first.getFields(), "(", ")", ",", ""));
        sb.append(" VALUES ");
        LOGGER.info("sb {}", sb);
        for (Record record: records ) {
            LOGGER.info("records {}", record.getJsonMap().keySet().stream().collect(Collectors.joining(",", "[", "]")));
            List<Object> values = record.getFields().
                    stream().
                    map(field -> record.getJsonMap().get(field.name())).
                    collect(Collectors.toList());
            String valueStr = convertWithStream(values, "(", ")", ",", "'");
            sb.append(valueStr + ",");
        }
        String insertStr = sb.deleteCharAt(sb.length() - 1).toString();
        long s2 = System.currentTimeMillis();

        try (ClickHouseClient client = ClickHouseClient.newInstance(ClickHouseProtocol.HTTP);
             ClickHouseResponse response = client.connect(server) // or client.connect(endpoints)
                     // you'll have to parse response manually if using a different format
                     .format(ClickHouseFormat.RowBinaryWithNamesAndTypes)
                     .query(insertStr)
                     .executeAndWait()) {
            ClickHouseResponseSummary summary = response.getSummary();
            long totalRows = summary.getTotalRowsToRead();
            LOGGER.info("totalRows {}", totalRows);

        } catch (ClickHouseException e) {
            LOGGER.error(insertStr);
            LOGGER.error("INSERT ", e);
            throw new RuntimeException(e);
        }
        long s3 = System.currentTimeMillis();
        LOGGER.info("batchSize {} data ms {} send {}", batchSize, s2 - s1, s3 - s2);
    }

    @Override
    public long recordsInserted() {
        return 0;
    }
}