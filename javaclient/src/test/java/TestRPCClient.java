
import confluo.rpc.*;
import org.apache.thrift.TException;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class TestRPCClient {

    private Process process;

    private void startServer() throws IOException, InterruptedException {
        String SERVER_EXECUTABLE = System.getenv("CONFLUO_SERVER_EXEC");
        if (SERVER_EXECUTABLE == null) {
            SERVER_EXECUTABLE = "confluod";
        }
        ProcessBuilder pb = new ProcessBuilder(SERVER_EXECUTABLE, "--data-path", "/tmp/javaclient");
        File log = new File("/tmp/java_test.txt");
        pb.redirectErrorStream(true);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(log));
        process = pb.start();
        waitTillServerReady();
    }

    @Before
    public void setUo() throws IOException, InterruptedException {
        startServer();
    }

    @After
    public void tearDown() throws InterruptedException {
        stopServer();
    }

    private void waitTillServerReady() throws InterruptedException {
        boolean check = true;
        while (check) {
            try {
                RPCClient c = new RPCClient("127.0.0.1", 9090);
                c.close();
                check = false;
            } catch (TException e) {
                Thread.sleep(100);
            }
        }
    }

    private void stopServer() throws InterruptedException {
        process.destroy();
        process.waitFor();
    }

    @Test
    public void testConcurrentConnections() throws TException, IOException, InterruptedException {
        List<RPCClient> clients = new ArrayList<>();
        clients.add(new RPCClient("127.0.0.1", 9090));
        clients.add(new RPCClient("127.0.0.1", 9090));
        clients.add(new RPCClient("127.0.0.1", 9090));
        clients.add(new RPCClient("127.0.0.1", 9090));

        for (RPCClient c : clients) {
            c.disconnect();
        }
    }

    @Test
    public void testCreateAtomicMultilog() throws TException, IOException, InterruptedException {
        RPCClient client = new RPCClient("127.0.0.1", 9090);
        SchemaBuilder builder = new SchemaBuilder();
        Schema multilogSchema = new Schema(builder.addColumn(DataTypes.STRING_TYPE(8), "msg").build());
        client.createAtomicMultilog("my_multilog", multilogSchema, StorageId.IN_MEMORY);
        client.disconnect();
    }

    @Test
    public void testReadWriteInMemory() throws TException, IOException, InterruptedException {
        RPCClient client = new RPCClient("127.0.0.1", 9090);
        SchemaBuilder builder = new SchemaBuilder();
        Schema multilogSchema = new Schema(builder.addColumn(DataTypes.STRING_TYPE(8), "msg").build());
        client.createAtomicMultilog("my_multilog", multilogSchema, StorageId.IN_MEMORY);
        int size = Long.SIZE / Byte.SIZE + 8;
        ByteBuffer data = ByteBuffer.allocate(size);
        data.putLong(System.nanoTime());
        data.put("abcdefgh".getBytes());
        data.flip();

        client.append(data);
        ByteBuffer buf = client.read(0);
        for (int i = 0; i < 8; i++) {
            Assert.assertEquals((char) ('a' + i), buf.get(i + 8));
        }

        client.disconnect();
    }

    @Test
    public void testReadWriteDurableRelaxed() throws TException, IOException, InterruptedException {
        RPCClient client = new RPCClient("127.0.0.1", 9090);
        SchemaBuilder builder = new SchemaBuilder();
        Schema multilogSchema = new Schema(builder.addColumn(DataTypes.STRING_TYPE(8), "msg").build());
        client.createAtomicMultilog("my_multilog", multilogSchema, StorageId.DURABLE_RELAXED);

        ByteBuffer data = ByteBuffer.allocate(Long.SIZE / Byte.SIZE + 8);
        data.putLong(System.nanoTime());
        data.put("abcdefgh".getBytes());
        data.flip();

        client.append(data);
        ByteBuffer buf = client.read(0);
        for (int i = 0; i < 8; i++) {
            Assert.assertEquals((char) ('a' + i), buf.get(i + 8));
        }

        client.disconnect();
    }

    @Test
    public void testReadWriteDurable() throws TException, IOException, InterruptedException {
        RPCClient client = new RPCClient("127.0.0.1", 9090);
        SchemaBuilder builder = new SchemaBuilder();
        Schema multilogSchema = new Schema(builder.addColumn(DataTypes.STRING_TYPE(8), "msg").build());
        client.createAtomicMultilog("my_multilog", multilogSchema, StorageId.DURABLE);

        ByteBuffer data = ByteBuffer.allocate(Long.SIZE / Byte.SIZE + 8);
        data.putLong(System.nanoTime());
        data.put("abcdefgh".getBytes());
        data.flip();

        client.append(data);
        ByteBuffer buf = client.read(0);
        for (int i = 0; i < 8; i++) {
            Assert.assertEquals((char) ('a' + i), buf.get(i + 8));
        }

        client.disconnect();
    }

    @Test
    public void testExecuteFilter() throws TException, IOException, InterruptedException {
        RPCClient client = new RPCClient("127.0.0.1", 9090);
        Schema multilogSchema = new Schema(buildSchema());
        client.createAtomicMultilog("my_multilog", multilogSchema, StorageId.IN_MEMORY);

        client.add_index("a", 1);
        client.add_index("b", 2);
        client.add_index("c", 10);
        client.add_index("d", 2);
        client.add_index("e", 100);
        client.add_index("f", 0.1);
        client.add_index("g", 0.01);
        client.add_index("h", 1);

        client.append(packRecord(true, "0", (short) 0, 0, 0, 0.01f, 0.01, "abc"));
        client.append(packRecord(true, "1", (short) 10, 2, 1, 0.1f, 0.02, "defg"));
        client.append(packRecord(false, "2", (short) 20, 4, 10, 0.2f, 0.03, "hijkl"));
        client.append(packRecord(true, "3", (short) 30, 6, 100, 0.3f, 0.04, "mnopqr"));
        client.append(packRecord(false, "4", (short) 40, 8, 1000, 0.4f, 0.05, "stuvwx"));
        client.append(packRecord(true, "5", (short) 50, 10, 10000, 0.5f, 0.06, "yyy"));
        client.append(packRecord(false, "6", (short) 60, 12, 100000, 0.6f, 0.07, "zzz"));
        client.append(packRecord(true, "7", (short) 70, 14, 1000000, 0.7f, 0.08, "zzz"));

        int i = 0;
        for (Record record : client.execute_filter("a == true")) {
            Assert.assertTrue(record.at(1).unpack() == 1);
            i += 1;
        }
        Assert.assertTrue(i == 4);

        i = 0;
        for (Record record : client.execute_filter("b > 4")) {
            Assert.assertTrue(record.at(2).unpack() > 4);
            i += 1;
        }
        Assert.assertTrue(i == 3);

        i = 0;
        for (Record record : client.execute_filter("c <= 30")) {
            Assert.assertTrue(record.at(3).unpack() <= 30);
            i += 1;
        }
        Assert.assertTrue(i == 4);

        i = 0;
        for (Record record : client.execute_filter("d == 0")) {
            Assert.assertTrue(record.at(4).unpack() == 0);
            i += 1;
        }
        Assert.assertTrue(i == 1);

        i = 0;
        for (Record record : client.execute_filter("e <= 100")) {
            Assert.assertTrue(record.at(5).unpack() <= 100);
            i += 1;
        }
        Assert.assertTrue(i == 4);

        i = 0;
        for (Record record : client.execute_filter("f > 0.1")) {
            Assert.assertTrue(record.at(6).unpack() > 0.1);
            i += 1;
        }
        Assert.assertTrue(i == 6);

        i = 0;
        for (Record record : client.execute_filter("g < 0.06")) {
            Assert.assertTrue(record.at(7).unpack() < 0.06);
            i += 1;
        }
        Assert.assertTrue(i == 5);

        i = 0;
        for (Record record : client.execute_filter("h == zzz")) {
            //Assert.assertTrue(Record.at(8).unpack());
            i += 1;
        }
        Assert.assertTrue(i == 2);

        i = 0;
        for (Record record : client.execute_filter("a == true && b > 4")) {
            Assert.assertTrue(record.at(1).unpack() == 1);
            Assert.assertTrue(record.at(2).unpack() > 4);
            i += 1;
        }
        Assert.assertTrue(i == 2);

        i = 0;
        for (Record record : client.execute_filter("a == true && (b > 4 || c <= 30)")) {
            Assert.assertTrue(record.at(1).unpack() == 1);
            Assert.assertTrue(record.at(2).unpack() > 4 || record.at(3).unpack() <= 30);
            i += 1;
        }
        Assert.assertTrue(i == 4);

        i = 0;
        for (Record record : client.execute_filter("a == true && (b > 4 || f > 0.1)")) {
            Assert.assertTrue(record.at(1).unpack() == 1);
            Assert.assertTrue(record.at(2).unpack() > 4 || record.at(6).unpack() > 0.1);
            i += 1;
        }

        client.disconnect();
    }

    @Test
    public void testQueryFilter() throws TException, IOException, InterruptedException {
        RPCClient client = new RPCClient("127.0.0.1", 9090);
        Schema multilogSchema = new Schema(buildSchema());

        client.createAtomicMultilog("my_multilog", multilogSchema, StorageId.IN_MEMORY);
        client.add_filter("filter1", "a == true");
        client.add_filter("filter2", "b > 4");
        client.add_filter("filter3", "c <= 30");
        client.add_filter("filter4", "d == 0");
        client.add_filter("filter5", "e <= 100");
        client.add_filter("filter6", "f > 0.1");
        client.add_filter("filter7", "g < 0.06");
        client.add_filter("filter8", "h == zzz");

        client.add_aggregate("agg1", "filter1", "SUM(d)");
        client.add_aggregate("agg2", "filter2", "SUM(d)");
        client.add_aggregate("agg3", "filter3", "SUM(d)");
        client.add_aggregate("agg4", "filter4", "SUM(d)");
        client.add_aggregate("agg5", "filter5", "SUM(d)");
        client.add_aggregate("agg6", "filter6", "SUM(d)");
        client.add_aggregate("agg7", "filter7", "SUM(d)");
        client.add_aggregate("agg8", "filter8", "SUM(d)");
        client.install_trigger("trigger1", "agg1 >= 10");
        client.install_trigger("trigger2", "agg2 >= 10");
        client.install_trigger("trigger3", "agg3 >= 10");
        client.install_trigger("trigger4", "agg4 >= 10");
        client.install_trigger("trigger5", "agg5 >= 10");
        client.install_trigger("trigger6", "agg6 >= 10");
        client.install_trigger("trigger7", "agg7 >= 10");
        client.install_trigger("trigger8", "agg8 >= 10");

        long now = System.nanoTime();
        long begin_ms = timeBlock(now);
        long end_ms = timeBlock(now);

        client.append(packRecordTime(now, false, '0', (short) 0, 0, 0, 0.0f, 0.01, "abc"));
        client.append(packRecordTime(now, true, '1', (short) 10, 2, 1, 0.1f, 0.02, "defg"));
        client.append(packRecordTime(now, false, '2', (short) 20, 4, 10, 0.2f, 0.03, "hijkl"));
        client.append(packRecordTime(now, true, '3', (short) 30, 6, 100, 0.3f, 0.04, "mnopqr"));
        client.append(packRecordTime(now, false, '4', (short) 40, 8, 1000, 0.4f, 0.05, "stuvwx"));
        client.append(packRecordTime(now, true, '5', (short) 50, 10, 10000, 0.5f, 0.06, "yyy"));
        client.append(packRecordTime(now, false, '6', (short) 60, 12, 100000, 0.6f, 0.07, "zzz"));
        client.append(packRecordTime(now, true, '7', (short) 70, 14, 1000000, 0.7f, 0.08, "zzz"));

        int i = 0;
        for (Record record : client.queryFilter("filter1", begin_ms, end_ms, "")) {
            Assert.assertEquals(1, record.at(1).getData().get(0));
            i += 1;
        }
        Assert.assertEquals(4, i);

        i = 0;
        for (Record record : client.queryFilter("filter2", begin_ms, end_ms, "")) {
            Assert.assertTrue(record.at(2).unpack() > 4);
            i += 1;
        }
        Assert.assertEquals(3, i);

        i = 0;
        for (Record record : client.queryFilter("filter3", begin_ms, end_ms, "")) {
            Assert.assertTrue(record.at(3).unpack() <= 30);
            i += 1;
        }
        Assert.assertEquals(4, i);

        i = 0;
        for (Record record : client.queryFilter("filter4", begin_ms, end_ms, "")) {
            Assert.assertTrue(record.at(4).unpack() == 0);
            i += 1;
        }
        Assert.assertEquals(1, i);

        i = 0;
        for (Record record : client.queryFilter("filter5", begin_ms, end_ms, "")) {
            Assert.assertTrue(record.at(5).unpack() <= 100);
            i += 1;
        }
        Assert.assertEquals(4, i);

        i = 0;
        for (Record record : client.queryFilter("filter6", begin_ms, end_ms, "")) {
            Assert.assertTrue(record.at(6).unpack() > 0.1);
            i += 1;
        }
        Assert.assertEquals(6, i);

        i = 0;
        for (Record record : client.queryFilter("filter7", begin_ms, end_ms, "")) {
            Assert.assertTrue(record.at(7).unpack() < 0.06);
            i += 1;
        }
        Assert.assertEquals(5, i);

        client.disconnect();
    }

    private List<Column> buildSchema() {
        SchemaBuilder builder = new SchemaBuilder();
        builder.addColumn(DataTypes.BOOL_TYPE, "a");
        builder.addColumn(DataTypes.CHAR_TYPE, "b");
        builder.addColumn(DataTypes.SHORT_TYPE, "c");
        builder.addColumn(DataTypes.INT_TYPE, "d");
        builder.addColumn(DataTypes.LONG_TYPE, "e");
        builder.addColumn(DataTypes.FLOAT_TYPE, "f");
        builder.addColumn(DataTypes.DOUBLE_TYPE, "g");
        builder.addColumn(DataTypes.STRING_TYPE(16), "h");
        return builder.build();
    }

    private ByteBuffer packRecord(boolean a, String b, short c, int d, long e, float f, double g, String h) {
        int size = 8 + 1 + 1 + 2 + 4 + 8 + 4 + 8 + 16;
        ByteBuffer rec = ByteBuffer.allocate(size);
        rec.putLong(System.nanoTime());
        rec.put((byte) (a ? 1 : 0));
        rec.put(b.getBytes());
        rec.putShort(c);
        rec.putInt(d);
        rec.putLong(e);
        rec.putFloat(f);
        rec.putDouble(g);
        rec.put(h.getBytes());
        rec.flip();
        return rec;
    }

    private ByteBuffer packRecordTime(long ts, boolean a, char b, short c, int d, long e, float f, double g, String h) {
        int size = 8 + 1 + 1 + 2 + 4 + 8 + 4 + 8 + 16;
        ByteBuffer rec = ByteBuffer.allocate(size);
        rec.putLong(ts);
        rec.put((byte) (a ? 1 : 0));
        rec.putChar(b);
        rec.putShort(c);
        rec.putInt(d);
        rec.putLong(e);
        rec.putFloat(f);
        rec.putDouble(g);
        rec.put(h.getBytes());
        rec.flip();
        return rec;
    }

    private long timeBlock(long ts) {
        return (long) (ts / 1e6);
    }

}
