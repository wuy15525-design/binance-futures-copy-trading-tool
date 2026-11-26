package com.localcopy.terminal;
import java.sql.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

final class ContentValues extends LinkedHashMap<String,Object> {void putNull(String k){put(k,null);}}
final class Cursor implements AutoCloseable {
    final List<Object[]> rows=new ArrayList<>();int index=-1;
    boolean moveToFirst(){index=0;return index<rows.size();}
    boolean moveToNext(){return ++index<rows.size();}
    String getString(int i){Object v=rows.get(index)[i];return v==null?null:v.toString();}
    long getLong(int i){String value=getString(i);return value==null?0:Long.parseLong(value);}
    int getInt(int i){return (int)getLong(i);}
    boolean isNull(int i){return rows.get(index)[i]==null;}
    public void close(){rows.clear();}
}
abstract class SQLiteOpenHelper {
    final DesktopContext ctx;final String name;final int version;SQLiteDatabase db;
    SQLiteOpenHelper(DesktopContext ctx,String name,Object ignored,int version){this.ctx=ctx;this.name=name;this.version=version;}
    void setWriteAheadLoggingEnabled(boolean on){}
    abstract void onCreate(SQLiteDatabase db);
    abstract void onUpgrade(SQLiteDatabase db,int old,int target);
    synchronized SQLiteDatabase getReadableDatabase(){return getWritableDatabase();}
    synchronized SQLiteDatabase getWritableDatabase(){
        if(db!=null)return db;
        try{SQLiteDatabase next=new SQLiteDatabase(DriverManager.getConnection("jdbc:sqlite:"+ctx.home.resolve(name)));
            try {
                next.execSQL("PRAGMA journal_mode=WAL");next.execSQL("PRAGMA busy_timeout=5000");next.execSQL("PRAGMA synchronous=FULL");
                next.beginTransaction();
                try(Cursor versionRow=next.rawQuery("PRAGMA user_version",null)){versionRow.moveToFirst();int old=versionRow.getInt(0);
                    if(old>version)throw new IllegalStateException("数据库版本过高");
                    if(old==0)onCreate(next);else if(old<version)onUpgrade(next,old,version);
                    next.execSQL("PRAGMA user_version="+version);next.setTransactionSuccessful();
                }finally{next.endTransaction();}
                db=next;return db;
            }catch(RuntimeException e){next.connection.close();throw e;}
        }catch(SQLException e){throw new IllegalStateException(e);}
    }
}
final class SQLiteDatabase{
    static final int CONFLICT_IGNORE=4,CONFLICT_REPLACE=5;
    final Connection connection;final ReentrantLock lock=new ReentrantLock(true);boolean successful;
    SQLiteDatabase(Connection c){connection=c;}
    void beginTransaction(){lock.lock();try{connection.setAutoCommit(false);successful=false;}catch(SQLException e){lock.unlock();throw new IllegalStateException(e);}}
    void setTransactionSuccessful(){successful=true;}
    void endTransaction(){try{if(successful)connection.commit();else connection.rollback();connection.setAutoCommit(true);}catch(SQLException e){throw new IllegalStateException(e);}finally{lock.unlock();}}
    void execSQL(String sql){execSQL(sql,null);}
    void execSQL(String sql,Object[] args){execute(sql,args,false);}
    private PreparedStatement prepare(String sql,Object[] args)throws SQLException{PreparedStatement st=connection.prepareStatement(sql);if(args!=null)for(int i=0;i<args.length;i++)st.setObject(i+1,args[i]);return st;}
    private int execute(String sql,Object[] args,boolean count){lock.lock();try(PreparedStatement st=prepare(sql,args)){if(count)return st.executeUpdate();st.execute();return 0;}catch(SQLException e){throw new IllegalStateException(e);}finally{lock.unlock();}}
    Cursor rawQuery(String sql,String[] args){lock.lock();try(PreparedStatement st=prepare(sql,args);ResultSet r=st.executeQuery()){
        Cursor result=new Cursor();int n=r.getMetaData().getColumnCount();while(r.next()){Object[] row=new Object[n];for(int i=0;i<n;i++)row[i]=r.getObject(i+1);result.rows.add(row);}return result;
    }catch(SQLException e){throw new IllegalStateException(e);}finally{lock.unlock();}}
    long insertOrThrow(String table,String unused,ContentValues data){return insertWithOnConflict(table,unused,data,0);}
    long insertWithOnConflict(String table,String unused,ContentValues data,int behavior){
        String policy=behavior==CONFLICT_IGNORE?" OR IGNORE":behavior==CONFLICT_REPLACE?" OR REPLACE":"";
        String sql="INSERT"+policy+" INTO "+table+" ("+String.join(",",data.keySet())+") VALUES ("+String.join(",",Collections.nCopies(data.size(),"?"))+")";
        lock.lock();try{if(execute(sql,data.values().toArray(),true)==0)return -1;try(Cursor r=rawQuery("SELECT last_insert_rowid()",null)){r.moveToFirst();return r.getLong(0);}}finally{lock.unlock();}
    }
    int update(String table,ContentValues data,String where,String[] args){List<Object> values=new ArrayList<>(data.values());if(args!=null)Collections.addAll(values,args);
        return execute("UPDATE "+table+" SET "+String.join("=?,",data.keySet())+"=? WHERE "+where,values.toArray(),true);}
}
