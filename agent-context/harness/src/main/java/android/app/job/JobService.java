package android.app.job;
public abstract class JobService extends android.app.Service {
    public abstract boolean onStartJob(JobParameters params);
    public abstract boolean onStopJob(JobParameters params);
    public final void jobFinished(JobParameters params, boolean reschedule) { }
    @Override public android.os.IBinder onBind(android.content.Intent intent) { return null; }
}
