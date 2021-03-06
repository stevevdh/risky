package au.gov.amsa.craft.analyzer.wms;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;
import java.io.File;
import java.io.FileFilter;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.GZIPOutputStream;

import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.Observer;
import rx.Subscriber;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Func1;
import rx.functions.Func2;
import rx.schedulers.Schedulers;
import au.gov.amsa.ais.LineAndTime;
import au.gov.amsa.ais.ShipTypeDecoder;
import au.gov.amsa.ais.rx.Streams;
import au.gov.amsa.navigation.DriftingDetector;
import au.gov.amsa.navigation.VesselClass;
import au.gov.amsa.navigation.VesselPosition;
import au.gov.amsa.navigation.VesselPosition.NavigationalStatus;
import au.gov.amsa.navigation.ais.AisVesselPositions;
import au.gov.amsa.navigation.ais.SortOperator;

import com.github.davidmoten.grumpy.projection.Projector;
import com.github.davidmoten.grumpy.wms.Layer;
import com.github.davidmoten.grumpy.wms.LayerFeatures;
import com.github.davidmoten.grumpy.wms.WmsRequest;
import com.github.davidmoten.grumpy.wms.WmsUtil;
import com.github.davidmoten.rx.Functions;
import com.github.davidmoten.rx.slf4j.Logging;
import com.google.common.base.Preconditions;

public class DriftingLayer implements Layer {

    private static final int SHIP_TYPE_FISHING = 30;
    private static final int SHIP_TYPE_DREDGING_OR_UNDERWATER_OPERATIONS = 33;
    private static final int SHIP_TYPE_TUG = 52;
    private static final int SHIP_TYPE_MILITARY_OPERATIONS = 35;
    private static final int SHIP_TYPE_LAW_ENFORCEMENT = 55;

    private static Logger log = LoggerFactory.getLogger(DriftingLayer.class);

    private final ConcurrentLinkedQueue<VesselPosition> queue = new ConcurrentLinkedQueue<VesselPosition>();

    public DriftingLayer() {

        // collect drifting candidates
        getDrifters()
        // add to queue
                .doOnNext(addToQueue())
                // run in background
                .subscribeOn(Schedulers.io())
                // subscribe
                .subscribe(createObserver());
    }

    private static Observable<VesselPosition> getDrifters() {
        return getFilenames()
        // need to leave a processor spare to process the merged items
        // and another for gc perhaps
                .buffer(Runtime.getRuntime().availableProcessors() - 1)
                // convert list to Observable
                .map(DriftingLayer.<String> iterableToObservable())
                // get positions for each window
                .concatMap(detectDrifters());
    }

    private static Observable<VesselPosition> getDriftingPositions(Observable<String> filenames) {
        return filenames
        // get the positions from each file
        // use concatMap till merge bug is fixed RxJava
        // https://github.com/ReactiveX/RxJava/issues/1941
        // log filename
                .lift(Logging.<String> logger().onNextPrefix("loading file=").showValue().log())
                // extract positions from file
                .flatMap(filenameToPositions())
                // log
                // .lift(Logging.<VesselPosition>logger().log())
                // only class A vessels
                .filter(onlyClassA())
                // ignore vessels at anchor
                .filter(not(atAnchor()))
                                // ignore vessels at moorings
                .filter(not(isMoored()))
                // ignore vessels that might be fishing
                .filter(not(isShipType(SHIP_TYPE_FISHING)))
                // ignore vessels that might be dredging
                .filter(not(isShipType(SHIP_TYPE_DREDGING_OR_UNDERWATER_OPERATIONS)))
                // ignore tugs
                .filter(not(isShipType(SHIP_TYPE_TUG)))
                // ignore military
                .filter(not(isShipType(SHIP_TYPE_MILITARY_OPERATIONS)))
                // ignore military
                .filter(not(isShipType(SHIP_TYPE_LAW_ENFORCEMENT)))
                // is a big vessel
                .filter(isBig())
                // group by id and date
                .distinct(byIdAndDay());
    }

    private static Func1<VesselPosition, Boolean> isShipType(final int shipType) {
        return new Func1<VesselPosition, Boolean>() {

            @Override
            public Boolean call(VesselPosition vp) {
                return vp.shipType().isPresent() && vp.shipType().get() == shipType;
            }};
    }

    public static <T> Func1<T, Boolean> not(final Func1<T, Boolean> f) {
        return new Func1<T, Boolean>() {
            @Override
            public Boolean call(T t) {
                return !f.call(t);
            }
        };
    }
    
    private static Observable<String> getFilenames() {
        List<String> filenames = new ArrayList<String>();
        final String filenameBase = "/media/analysis/nmea/2014/sorted-NMEA_ITU_201407";
        for (int i = 1; i <= 31; i++) {
            String filename = filenameBase + new DecimalFormat("00").format(i) + ".gz";
            if (new File(filename).exists()) {
                filenames.add(filename);
                log.info("adding filename " + filename);
            }
        }
        return Observable.from(filenames);
    }

    private static Func1<VesselPosition, Boolean> isBig() {
        return new Func1<VesselPosition, Boolean>() {
            @Override
            public Boolean call(VesselPosition p) {
                return !p.lengthMetres().isPresent() || p.lengthMetres().get() > 50;
            }
        };
    }

    private static Func1<VesselPosition, Boolean> onlyClassA() {
        return new Func1<VesselPosition, Boolean>() {
            @Override
            public Boolean call(VesselPosition p) {
                return p.cls() == VesselClass.A;
            }
        };
    }

    private static Func1<VesselPosition, Boolean> atAnchor() {
        return new Func1<VesselPosition, Boolean>() {
            @Override
            public Boolean call(VesselPosition p) {
                return p.navigationalStatus()== NavigationalStatus.AT_ANCHOR;
            }
        };
    }
    
    private static Func1<VesselPosition, Boolean> isMoored() {
        return new Func1<VesselPosition, Boolean>() {
            @Override
            public Boolean call(VesselPosition p) {
                return p.navigationalStatus()== NavigationalStatus.MOORED;
            }
        };
    }

    private static AtomicLong totalCount = new AtomicLong();

    private static Func1<String, Observable<VesselPosition>> filenameToPositions() {
        return new Func1<String, Observable<VesselPosition>>() {
            @Override
            public Observable<VesselPosition> call(final String filename) {
                return AisVesselPositions
                // read positions
                        .positions(Streams.nmeaFromGzip(filename))
                        // log requests
                        .doOnRequest(new Action1<Long>() {
                            @Override
                            public void call(Long n) {
                                // log.info("requested=" + n);
                            }
                        }).doOnNext(new Action1<VesselPosition>() {
                            final long startTime = System.currentTimeMillis();
                            long lastTime = System.currentTimeMillis();
                            DecimalFormat df = new DecimalFormat("0");
                            @Override
                            public void call(VesselPosition vp) {
                                long n = 100000;
                                if (totalCount.incrementAndGet() % n == 0) {
                                    long now = System.currentTimeMillis();
                                    final double rate;
                                    if (now == lastTime)
                                        rate = -1;
                                    else {
                                        rate = n / (double) (now - lastTime) * 1000d;
                                    }
                                    lastTime = now;
                                    final double rateSinceStart;
                                    if (now == startTime)
                                        rateSinceStart = -1;
                                    else 
                                        rateSinceStart = totalCount.get()/(double)(now - startTime) * 1000d;
                                    log.info("totalCount=" + totalCount.get() + ", msgsPerSecond="
                                            + df.format(rate) + ", msgPerSecondOverall=" + df.format(rateSinceStart));
                                }
                            }
                        })
                        // detect drift
                        .compose(DriftingDetector.detectDrift())
                        // backpressure strategy - don't
                        // .onBackpressureBlock()
                        // in background thread from pool per file
                        .subscribeOn(Schedulers.computation())
                        // log completion of read of file
                        .doOnCompleted(new Action0() {
                            @Override
                            public void call() {
                                log.info("finished " + filename);
                            }
                        });
            }
        };
    }

    private Observer<VesselPosition> createObserver() {
        return new Observer<VesselPosition>() {

            @Override
            public void onCompleted() {
                System.out.println("done");
            }

            @Override
            public void onError(Throwable e) {
                log.error(e.getMessage(), e);
            }

            @Override
            public void onNext(VesselPosition t) {
                // do nothing
            }
        };
    }

    private Action1<VesselPosition> addToQueue() {
        return new Action1<VesselPosition>() {

            @Override
            public void call(VesselPosition p) {
                // System.out.println(p.lat() + "\t" + p.lon() + "\t"
                // + p.id().uniqueId());
                if (queue.size() % 1000 == 0)
                    System.out.println("queue.size=" + queue.size());
                queue.add(p);
            }
        };
    }

    private static Func1<VesselPosition, String> byIdAndDay() {
        return new Func1<VesselPosition, String>() {
            final DateTimeFormatter format = DateTimeFormat.forPattern("yyyy-MM-dd");
            @Override
            public String call(VesselPosition p) {
                return p.id().uniqueId() + format.print(p.time());
            }
        };
    }

    public static final Func2<VesselPosition, VesselPosition, Integer> SORT_BY_TIME = new Func2<VesselPosition, VesselPosition, Integer>() {

        @Override
        public Integer call(VesselPosition p1, VesselPosition p2) {
            return ((Long) p1.time()).compareTo(p2.time());
        }
    };

    @Override
    public LayerFeatures getFeatures() {
        return LayerFeatures.builder().crs("EPSG:4326").crs("EPSG:3857").name("Drifting")
                .queryable().build();
    }

    @Override
    public String getInfo(Date time, WmsRequest request, final Point point, String mimeType) {

        final int HOTSPOT_SIZE = 5;

        final Projector projector = WmsUtil.getProjector(request);
        final StringBuilder response = new StringBuilder();
        response.append("<html>");
        Observable.from(queue)
        // only vessel positions close to the click point
                .filter(new Func1<VesselPosition, Boolean>() {
                    @Override
                    public Boolean call(VesselPosition p) {
                        Point pt = projector.toPoint(p.lat(), p.lon());
                        return Math.abs(point.x - pt.x) <= HOTSPOT_SIZE
                                && Math.abs(point.y - pt.y) <= HOTSPOT_SIZE;
                    }
                })
                // add html fragment for each vessel position to the response
                .doOnNext(new Action1<VesselPosition>() {
                    @Override
                    public void call(VesselPosition p) {
                        response.append("<p>");
                        response.append("<a href=\"https://www.fleetmon.com/en/vessels?s="
                                + p.id().uniqueId() + "\">mmsi=" + p.id().uniqueId()
                                + "</a>, time=" + new Date(p.time()));
                        if (p.shipType().isPresent()) {
                            response.append(", ");
                            response.append(ShipTypeDecoder.getShipType(p.shipType().get()));
                        }
                        response.append("</p>");
                        response.append("<p>");
                        response.append(p.toString());
                        response.append("</p>");
                    }
                })
                // go!
                .subscribe();
        response.append("</html>");
        return response.toString();
    }

    @Override
    public void render(Graphics2D g, WmsRequest request) {
        log.info("request=" + request);
        log.info("drawing " + queue.size() + " positions");
        final Projector projector = WmsUtil.getProjector(request);
        for (VesselPosition p : queue) {
            Point point = projector.toPoint(p.lat(), p.lon());
            g.setColor(Color.red);
            g.drawRect(point.x, point.y, 1, 1);
        }
        log.info("drawn");

    }

    private static void sortFile(String filename) throws FileNotFoundException, IOException {
        Comparator<LineAndTime> comparator = new Comparator<LineAndTime>() {
            @Override
            public int compare(LineAndTime line1, LineAndTime line2) {
                return ((Long) line1.getTime()).compareTo(line2.getTime());
            }
        };
        final File in = new File(filename);
        final File outFile = new File(in.getParentFile(), "sorted-" + in.getName());
        if (outFile.exists()) {
            log.info("file exists: " + outFile);
            return;
        }
        final OutputStreamWriter out = new OutputStreamWriter(new GZIPOutputStream(
                new FileOutputStream(outFile)), StandardCharsets.UTF_8);

        Streams
        // read from file
        .nmeaFromGzip(filename)
        // get time
                .flatMap(Streams.toLineAndTime())
                // sort
                .lift(new SortOperator<LineAndTime>(comparator, 20000000))
                // .lift(Logging.<LineAndTime> logger().showValue().log())
                .doOnCompleted(new Action0() {
                    @Override
                    public void call() {
                        try {
                            out.close();
                        } catch (IOException e) {
                        }
                    }
                }).forEach(new Action1<LineAndTime>() {
                    @Override
                    public void call(LineAndTime line) {
                        try {
                            out.write(line.getLine());
                            out.write('\n');
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    }
                });
    }

    private static void sortFiles() throws FileNotFoundException, IOException {
        // String filename = "/media/analysis/nmea/2014/NMEA_ITU_20140701.gz";
        File directory = new File("/media/analysis/nmea/2014");
        Preconditions.checkArgument(directory.exists());
        File[] files = directory.listFiles(new FileFilter() {
            @Override
            public boolean accept(File f) {
                return f.getName().startsWith("NMEA_") && f.getName().endsWith(".gz");
            }
        });
        int count = 0;

        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File f1, File f2) {
                return f1.getPath().compareTo(f2.getPath());
            }
        });

        for (File file : files) {
            count++;
            log.info("sorting " + count + " of " + files.length + ": " + file);
            sortFile(file.getAbsolutePath());
        }
    }

    private static <T> Func1<Iterable<T>, Observable<T>> iterableToObservable() {
        return new Func1<Iterable<T>, Observable<T>>() {
            @Override
            public Observable<T> call(Iterable<T> iterable) {
                return Observable.from(iterable);
            }
        };
    }

    private static Func1<Observable<String>, Observable<VesselPosition>> detectDrifters() {
        return new Func1<Observable<String>, Observable<VesselPosition>>() {
            @Override
            public Observable<VesselPosition> call(Observable<String> filenames) {
                return getDriftingPositions(filenames);
            }
        };
    }

    public static void main(String[] args) throws FileNotFoundException, IOException,
            InterruptedException {

        getDrifters()
                // log
                .lift(Logging.<VesselPosition> logger().showCount()
                        .showRateSinceStart("msgPerSecond").showMemory().every(5000).log())
                // subscribe
                .subscribe(new Subscriber<VesselPosition>() {

                    @Override
                    public void onStart() {
                    }

                    @Override
                    public void onCompleted() {
                        // TODO Auto-generated method stub

                    }

                    @Override
                    public void onError(Throwable e) {
                        log.error(e.getMessage(), e);
                        throw new RuntimeException(e);
                    }

                    @Override
                    public void onNext(VesselPosition vp) {
                        if (vp.shipType().isPresent() && false) {
                            System.out.println(vp.id() + "," + vp.shipType() + ","
                                    + ShipTypeDecoder.getShipType(vp.shipType().get())
                                    + ", length=" + vp.lengthMetres() + ", cog=" + vp.cogDegrees()
                                    + ", heading=" + vp.headingDegrees() + ", speedKnots="
                                    + (vp.speedMetresPerSecond().get() / 1852.0 * 3600));
                        }
                    }
                });

        Thread.sleep(10000000);
    }

}
