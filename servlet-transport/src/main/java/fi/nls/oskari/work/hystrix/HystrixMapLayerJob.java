package fi.nls.oskari.work.hystrix;

import com.netflix.hystrix.exception.HystrixBadRequestException;
import fi.nls.oskari.log.LogFactory;
import fi.nls.oskari.log.Logger;
import fi.nls.oskari.work.JobValidator;
import fi.nls.oskari.work.OWSMapLayerJob;

/**
 * Job for WFS Map Layer
 */
public class HystrixMapLayerJob extends HystrixJob {

    protected static final Logger log = LogFactory
            .getLogger(HystrixMapLayerJob.class);

    private OWSMapLayerJob job;

    /**
     * Creates a new runnable job with own Jedis instance
     * <p/>
     * Parameters define client's service (communication channel), session and
     * layer's id. Also sets resources that will be sent if the layer
     * configuration allows.
     */
    public HystrixMapLayerJob(OWSMapLayerJob job) {
        super("transport", "LayerJob_" + job.getLayerId() + "_" + job.getType().toString());
        this.job = job;
    }

    public  String getJobId() {
        return job.getLayerId() + "." + job.getType().toString();
    }
    /**
     * Unique key definition
     */
    public String getKey() {
        return job.getKey();
    }

    @Override
    public void terminate() {
        super.terminate();
        job.terminate();
    }

    /**
     * Process of the job
     * <p/>
     * Worker calls this when starts the job.
     */
    public String run() {
        setStartTime();
        notifyStart();
        JobValidator validator = new HystrixJobValidator(job);
        if(!validator.validateJob()) {
            throw new HystrixBadRequestException("Validation failed for (" +  job.getKey() + ")");
        }
        final String value = job.run();
        return value;
    }

    public void notifyStart() {
        job.notifyStart();
    }

    public void notifyCompleted(boolean success) {
        job.notifyCompleted(success);
    }

    // TODO: check if this actually works!
    public String getFallback() {
        job.notifyError();
        return "success";
    }
}