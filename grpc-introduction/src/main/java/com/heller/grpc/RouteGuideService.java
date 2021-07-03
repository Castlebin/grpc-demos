package com.heller.grpc;

import static java.lang.Math.atan2;
import static java.lang.Math.cos;
import static java.lang.Math.max;
import static java.lang.Math.min;
import static java.lang.Math.sin;
import static java.lang.Math.sqrt;
import static java.lang.Math.toRadians;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import io.grpc.examples.routeguide.RouteGuideGrpc;
import io.grpc.examples.routeguide.RouteGuideOuterClass.Feature;
import io.grpc.examples.routeguide.RouteGuideOuterClass.Point;
import io.grpc.examples.routeguide.RouteGuideOuterClass.Rectangle;
import io.grpc.examples.routeguide.RouteGuideOuterClass.RouteNote;
import io.grpc.examples.routeguide.RouteGuideOuterClass.RouteSummary;
import io.grpc.stub.StreamObserver;

/**
 * 复写 RouteGuideGrpc.RouteGuideImplBase 中的方法，来实现我们的 RPC 服务端 Service
 */
public class RouteGuideService extends RouteGuideGrpc.RouteGuideImplBase {
    private static final Logger logger = Logger.getLogger(RouteGuideService.class.getName());

    private final Collection<Feature> features;
    private final ConcurrentMap<Point, List<RouteNote>> routeNotes = new ConcurrentHashMap<>();

    public RouteGuideService(Collection<Feature> features) {
        this.features = features;
    }

    /**
     * Simple RPC
     */
    @Override
    public void getFeature(Point request, StreamObserver<Feature> responseObserver) {
        // 要返回的结果
        Feature feature = checkFeature(request);

        // 将结果塞到返回里
        responseObserver.onNext(feature);
        // 表示服务端本次请求处理完毕了
        responseObserver.onCompleted();
    }

    private Feature checkFeature(Point location) {
        for (Feature feature : features) {
            if (feature.getLocation().getLatitude() == location.getLatitude()
                    && feature.getLocation().getLongitude() == location.getLongitude()) {
                return feature;
            }
        }

        // No feature was found, return an unnamed feature.
        return Feature.newBuilder().setName("").setLocation(location).build();
    }

    /**
     * Server-side streaming RPC
     *
     * 服务端 流式 返回请求结果，返回 **多个** Feature 给客户端
     * 注意，方法的声明和上面 （@see getFeature） 最普通的 RPC 是一样的
     */
    @Override
    public void listFeatures(Rectangle request, StreamObserver<Feature> responseObserver) {
        int left = min(request.getLo().getLongitude(), request.getHi().getLongitude());
        int right = max(request.getLo().getLongitude(), request.getHi().getLongitude());
        int top = max(request.getLo().getLatitude(), request.getHi().getLatitude());
        int bottom = min(request.getLo().getLatitude(), request.getHi().getLatitude());

        for (Feature feature : features) {
            if (!RouteGuideUtil.exists(feature)) {
                continue;
            }

            int lat = feature.getLocation().getLatitude();
            int lon = feature.getLocation().getLongitude();
            if (lon >= left && lon <= right && lat >= bottom && lat <= top) {
                // for 循环，流式返回多个 Feature 给客户端
                responseObserver.onNext(feature);
            }
        }

        // 表示服务端处理完毕，服务端处理本次请求结束
        responseObserver.onCompleted();
    }

    /**
     * 客户端流式RPC （Client-side streaming RPC）
     *
     * 从客户端获取一个 Points 流（多个 Point 对象），并返回一个包含他们 处理后信息的 RouteSummary 对象
     *
     * 通过实现 StreamObserver 对应的回调方法
     */
    @Override
    public StreamObserver<Point> recodeRoute(StreamObserver<RouteSummary> responseObserver) {
        return new StreamObserver<Point>() {
            int pointCount;
            int featureCount;
            int distance;
            Point previous;
            long startTime = System.nanoTime();

            // 客户端通过 流 每发送一个 Point 过来，就会调用这个方法
            @Override
            public void onNext(Point point) {
                pointCount++;
                if (RouteGuideUtil.exists(checkFeature(point))) {
                    featureCount++;
                }
                // For each point after the first, add the incremental distance from the previous point
                // to the total distance value.
                if (previous != null) {
                    distance += calcDistance(previous, point);
                }
                previous = point;
            }

            @Override
            public void onError(Throwable t) {
                logger.log(Level.WARNING, "Encountered error in recordRoute", t);
            }

            /**
             * 客户端发送数据完毕后，grpc服务端 会回调这个方法
             */
            @Override
            public void onCompleted() {
                long seconds = NANOSECONDS.toSeconds(System.nanoTime() - startTime);

                RouteSummary routeSummary = RouteSummary.newBuilder().setPointCount(pointCount)
                        .setFeatureCount(featureCount).setDistance(distance)
                        .setElapsedTime((int) seconds).build();

                // 将要返回的单个数据对象塞进返回结果里
                responseObserver.onNext(routeSummary);

                // 服务端处理完毕本次请求
                responseObserver.onCompleted();
            }
        };
    }

    private static int calcDistance(Point start, Point end) {
        int r = 6371000; // earth radius in meters
        double lat1 = toRadians(RouteGuideUtil.getLatitude(start));
        double lat2 = toRadians(RouteGuideUtil.getLatitude(end));
        double lon1 = toRadians(RouteGuideUtil.getLongitude(start));
        double lon2 = toRadians(RouteGuideUtil.getLongitude(end));
        double deltaLat = lat2 - lat1;
        double deltaLon = lon2 - lon1;

        double a = sin(deltaLat / 2) * sin(deltaLat / 2)
                + cos(lat1) * cos(lat2) * sin(deltaLon / 2) * sin(deltaLon / 2);
        double c = 2 * atan2(sqrt(a), sqrt(1 - a));

        return (int) (r * c);
    }

    /**
     * 双向的流式 RPC （Bidirectional streaming RPC ）
     *
     * 可以看到，** 和客户端 流式RPC 的方法声明是一样的 **
     */
    @Override
    public StreamObserver<RouteNote> routeChat(StreamObserver<RouteNote> responseObserver) {
        return new StreamObserver<RouteNote>() {

            /**
             * 客户端通过流每发送一个 RouteNote 过来，都会回调本方法
             */
            @Override
            public void onNext(RouteNote note) {
                List<RouteNote> notes = getOrCreateNotes(note.getLocation());

                // Respond with all previous notes at this location.
                for (RouteNote prevNote : notes.toArray(new RouteNote[0])) {
                    // 服务端将一个 RouteNote 对象塞到返回流里
                    responseObserver.onNext(prevNote);
                }

                // Now add the new note to the list
                notes.add(note);
            }

            @Override
            public void onError(Throwable t) {
                logger.log(Level.WARNING, "Encountered error in routeChat", t);
            }

            @Override
            public void onCompleted() {
                responseObserver.onCompleted();
            }
        };
    }

    /**
     * Get the notes list for the given location. If missing, create it.
     */
    private List<RouteNote> getOrCreateNotes(Point location) {
        List<RouteNote> notes = Collections.synchronizedList(new ArrayList<>());
        List<RouteNote> prevNotes = routeNotes.putIfAbsent(location, notes);
        return prevNotes != null ? prevNotes : notes;
    }

}
