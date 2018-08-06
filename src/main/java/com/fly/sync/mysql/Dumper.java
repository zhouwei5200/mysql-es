package com.fly.sync.mysql;

import com.fly.sync.action.ChangePostionAction;
import com.fly.sync.action.InsertAction;
import com.fly.sync.contract.AbstractAction;
import com.fly.sync.contract.DbFactory;
import com.fly.sync.es.Es;
import com.fly.sync.exception.FatalDumpException;
import com.fly.sync.mysql.parser.InsertParser;
import com.fly.sync.mysql.parser.PositionParser;
import com.fly.sync.setting.BinLog;
import com.fly.sync.setting.Config;
import com.fly.sync.setting.River;
import com.sun.istack.internal.NotNull;
import io.reactivex.Observable;
import io.reactivex.Scheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;

public class Dumper implements DbFactory {

    private Config config;
    private River river;
    private DbFactory dbFactory;
    private BinLog.Position position = new BinLog.Position();

    public final static Logger logger = LoggerFactory.getLogger(Dumper.class);

    public Dumper(@NotNull Config config, @NotNull River river, DbFactory dbFactory)
    {
        this.config = config;
        this.river = river;
        this.dbFactory = dbFactory;
    }

    @Override
    public Es getEs() {
        return dbFactory.getEs();
    }

    @Override
    public MySql getMySql() {
        return dbFactory.getMySql();
    }

    @Override
    public River.Database getRiverDatabase() {
        return dbFactory.getRiverDatabase();
    }

    public Observable<AbstractAction> run(Scheduler scheduler)
    {
        StringBuilder cmd = new StringBuilder();

        /*
         * /usr/bin/mysqldump --host=XXXX --port=3306 --user=root --password=x xxxxxxxxxxxx \
         * --master-data --single-transaction --skip-lock-tables --compact --skip-opt \
         * --quick --no-create-info --skip-extended-insert --set-gtid-purged=OFF --default-character-set=utf8 \
         * db table1 table2 table3 table4
         */
        River.Database database = getRiverDatabase();
        cmd.append(config.mysqldump)
            .append(" --host=")
            .append(river.my.host)
            .append(" --port=")
            .append(river.my.port)
            .append(" --user=")
            .append(river.my.user)
            .append(" --password=")
            .append(river.my.password)
            .append(" --default-character-set=")
            .append(river.charset)
            .append(" --master-data --single-transaction --skip-lock-tables --compact --skip-opt --quick --no-create-info --skip-extended-insert --set-gtid-purged=OFF ")
            .append(database.db);

        for (String name: database.tables.keySet()
             ) {
            cmd.append(" ");
            cmd.append(name);
        }

        logger.info(cmd.toString().replace(river.my.password, "*"));
        Process process;

        try {
            process = Runtime.getRuntime().exec(cmd.toString());

        } catch (IOException e)
        {
            return Observable.error(new FatalDumpException(e));
        }

        logger.info("Dump database [{}] from mysqldump.", database.db);

        return Observable.merge(
                errorObservable(process).subscribeOn(scheduler),
                dataObservable(process).subscribeOn(scheduler)
            )
            .doOnError(
                    throwable -> position.reset()
            );

    }

    private Observable<AbstractAction> dataObservable(final Process process)
    {
        return Observable.create(observableEmitter -> {
            String sql;
            BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            try {
                while(true)
                {
                    sql = bufferedReader.readLine();
                    if (sql == null)
                        break;

                    if (sql.startsWith("CHANGE MASTER TO MASTER_LOG_FILE"))
                    {
                        parsePosition(sql);
                    } else if (sql.startsWith("INSERT INTO")){

                        InsertAction insertAction = parseInsert(sql);
                        if (insertAction == null)
                            logger.warn("SQL \"{}\" invalid.", sql);
                        else
                            observableEmitter.onNext(insertAction);
                    } else {
                        logger.info("Skip SQL {} ", sql);
                    }
                }

                if (!position.isEmpty())
                    observableEmitter.onNext(ChangePostionAction.create(position));

                observableEmitter.onComplete();
            } catch (IOException e)
            {
                observableEmitter.onError(new FatalDumpException(e));

            } finally {
                try {
                    bufferedReader.close();
                } catch (Exception e) {}
            }
            logger.info("Dump database: [{}] complete;", getRiverDatabase().db);

        });
    }

    private Observable<AbstractAction> errorObservable(final Process process){

        return Observable.create(observableEmitter -> {
            String s;
            BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));

            try {
                while(true)
                {

                    s = bufferedReader.readLine();
                    if (s == null)
                        break;

                    if (s.contains("[Warning]"))
                        continue;

                    observableEmitter.onError(new FatalDumpException(s));
                }

                observableEmitter.onComplete();

            } catch (IOException e)
            {
                observableEmitter.onError(new FatalDumpException(e));

            } finally {
                try {
                    bufferedReader.close();
                } catch (Exception e) {}
            }

        });
    }

    private synchronized void parsePosition(String sql) {
        BinLog.Position position = PositionParser.parse(sql);
        if (position != null)
            this.position.updateFrom(position);
    }

    private InsertAction parseInsert(String sql)
    {
        String table = InsertParser.parseTable(sql);
        if (table != null && !table.isEmpty())
        {
            List<Object> value = InsertParser.parseValue(sql);
            return value == null ? null : InsertAction.create(table, getMySql().columns(getRiverDatabase().db, table), value);
        }

        return null;
    }

}
