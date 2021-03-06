package com.elex.bigdata.zergling.etl.hbase;

import com.elex.bigdata.zergling.etl.ETLUtils;
import com.elex.bigdata.zergling.etl.InternalQueue;
import com.elex.bigdata.zergling.etl.model.ColumnInfo;
import com.elex.bigdata.zergling.etl.model.LogBatch;
import com.elex.bigdata.zergling.etl.model.NavigatorLog;
import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.hbase.client.HTableInterface;
import org.apache.hadoop.hbase.client.Put;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * User: Z J Wu Date: 14-2-26 Time: 上午9:48 Package: com.elex.bigdata.zergling.etl.hbase
 */
public class HBasePutter implements Runnable {

  private static final Logger LOGGER = Logger.getLogger(HBasePutter.class);

  private InternalQueue<LogBatch<NavigatorLog>> queue;

  private CountDownLatch signal;

  private HTableInterface hTable;

  private boolean onlyShow;

  private PutterCounter counter;

  public HBasePutter(InternalQueue<LogBatch<NavigatorLog>> queue, CountDownLatch signal, HTableInterface hTable,
                     boolean onlyShow, PutterCounter counter) {
    this.queue = queue;
    this.signal = signal;
    this.hTable = hTable;
    this.onlyShow = onlyShow;
    this.counter = counter;
  }

  private String purePut(List<Put> puts) {
    try {
      hTable.put(puts);
      return null;
    } catch (Exception e) {
      return e.getClass().getName();
    }
  }

  @Override
  public void run() {
    LogBatch<NavigatorLog> batch;
    Put put;
    List<NavigatorLog> content;
    byte[] rowkeyBytes;
    List<ColumnInfo> columnInfos;
    List<Put> puts;
    try {
      while (true) {
        batch = queue.take();
        if (batch == null || batch.isEmpty()) {
          continue;
        }
        if (batch.isPill()) {
          LOGGER.warn(Thread.currentThread().getName() + " pill received, quit now.");
          break;
        }
        content = batch.getContent();
        int outerVersion = batch.getVersion();
        puts = new ArrayList<>(content.size());
        int innerVersion = 0;
        long v;
        for (NavigatorLog log : content) {
          rowkeyBytes = log.getRowkey();
          try {
            columnInfos = log.getColumnInfos();
          } catch (IllegalAccessException e) {
            continue;
          }
          v = ETLUtils.makeVersion(outerVersion, innerVersion);
          put = new Put(rowkeyBytes);
          for (ColumnInfo ci : columnInfos) {
            put.add(ci.getColumnFamilyBytes(), ci.getQualifierBytes(), v, ci.getValueBytes());
          }
          puts.add(put);
          ++innerVersion;
        }
        if (onlyShow) {
          continue;
        }

        long t1 = System.currentTimeMillis();
        String returnResult = purePut(puts);
        boolean ok = StringUtils.isBlank(returnResult);
        long t2 = System.currentTimeMillis();
        long thisRoundTime = t2 - t1;
        if (ok) {
          counter.incVal(batch.size());
          if (thisRoundTime > 50) {
            LOGGER.info(
              "Put hbase ok but too slow, " + Thread.currentThread().getName() + " used " + thisRoundTime + " millis.");
          }
        } else {
          LOGGER.info("Put hbase error(" + Thread.currentThread().getName() + " in " + thisRoundTime + " millis.");
        }
      }
    } catch (InterruptedException e) {
      LOGGER.warn(Thread.currentThread().getName() + " is interrupt.");
      Thread.currentThread().interrupt();
    } finally {
      HBaseResourceManager.closeHTable(hTable);
      signal.countDown();
    }
  }
}
