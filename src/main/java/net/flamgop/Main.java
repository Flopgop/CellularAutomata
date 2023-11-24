package net.flamgop;

import org.jocl.*;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;


public class Main {

    private static final int N = 512;
    private static final int W = 512;

    private static final String src = """
            uchar iterate(uchar neighbors[9]) {
                uchar currentState = neighbors[4];
                uchar liveNeighbors = 0;
               
                for (int i = 0; i < 9; i++) {
                    if (i == 4) continue;
                    if (neighbors[i] == 1) {
                        liveNeighbors++;
                    }
                }
               
                if (currentState == 1) {
                    if (liveNeighbors < 2 || liveNeighbors > 3) {
                        return 0;
                    } else {
                        return 1;
                    }
                } else {
                    if (liveNeighbors == 3) {
                        return 1;
                    } else {
                        return 0;
                    }
                }
            }
                        
            __kernel void sampleKernel(
                __global const uchar *input,
                __global uchar *output
            ) {
                        
                int globalX = get_global_id(0);
                int globalY = get_global_id(1);
               
                int localX = get_local_id(0);
                int localY = get_local_id(1);
               
                int globalWidth = get_global_size(0);
                int globalHeight = get_global_size(1);
               
                int localWidth = get_local_size(0);
                int localHeight = get_local_size(1);
               
                // Calculate the offset of the current work-group within the global space
                int offsetX = get_group_id(0) * get_local_size(0);
                int offsetY = get_group_id(1) * get_local_size(1);
               
                uchar neighbors[9];
                int indices[9][2] = {
                    {-1,-1}, {-1, 0}, {-1, 1},
                    {0, -1}, { 0, 0}, { 0, 1},
                    {1, -1}, { 1, 0}, { 1, 1}
                };
               
                for (int i = 0; i < 9; i++) {
                    int nX = localX + indices[i][0];
                    int nY = localY + indices[i][1];
                   
                    // Adjust neighbor indices within the work-group boundaries
                    if (nX < 0) {
                        nX = 0;
                    } else if (nX >= localWidth) {
                        nX = localWidth - 1;
                    }
                   
                    if (nY < 0) {
                        nY = 0;
                    } else if (nY >= localHeight) {
                        nY = localHeight - 1;
                    }
                   
                    int nI = (offsetY + nY) * globalWidth + (offsetX + nX);
                    neighbors[i] = input[nI];
                }
               
                int currentIndex = (globalY * globalWidth) + globalX;
                output[currentIndex] = iterate(neighbors);
            }
            """;

    private static double scaleFactor = 1;

    private static class BufferDrawer extends JPanel {

        private byte[] framebuffer;
        private static final int w = 1;
        private static final int h = 1;

        BufferDrawer(byte[] framebuffer) {
            this.framebuffer = framebuffer;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);

            Graphics2D gfx = (Graphics2D) g.create(); // Create a copy of the graphics context
            gfx.setBackground(Color.BLACK);
            gfx.scale(scaleFactor, scaleFactor);

            for (int i = 0; i < N; i++) {
                for (int j = 0; j < W; j++) {
                    byte b = framebuffer[i*N+j];
                    gfx.setColor(b == (byte)0 ? Color.BLACK : Color.WHITE);
                    gfx.fillRect(i*w, j*h, w, h);
                }
            }
            gfx.dispose();
        }

        void setFramebuffer(byte[] image) {
            this.framebuffer = image;
            repaint();
        }
    }

    public static void main(String[] args) throws InterruptedException {

        /// MY DATA

        byte[] a = new byte[N * W];
        byte[] dst = new byte[N * W];

        for (int i = 0; i < N; i++) {
            for (int j = 0; j < W; j++) {
                a[i * N + j] = (byte) (ThreadLocalRandom.current().nextInt() % 2);
            }
        }

        Pointer ap = Pointer.to(a);
        Pointer dstp = Pointer.to(dst);

        // GUI SHIT

        BufferDrawer drawer = new BufferDrawer(a);
        Thread renderThread = new Thread(() -> {
            JFrame frame = new JFrame("Balls");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setSize(1024, 1024);
            frame.setResizable(false);
            frame.addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    switch (e.getKeyCode()) {
                        case KeyEvent.VK_MINUS -> scaleFactor -= 0.1;
                        case KeyEvent.VK_PLUS -> scaleFactor += 0.1;
                    }
                }
            });
            frame.add(drawer);
            frame.setVisible(true);
        });
        renderThread.start();

        /// INIT

        final int platformIndex = 0;
        final long deviceType = CL.CL_DEVICE_TYPE_ALL;
        final int deviceIndex = 0;

        CL.setExceptionsEnabled(true);

        int[] numPlatformsArray = new int[1];
        CL.clGetPlatformIDs(0, null, numPlatformsArray);
        int numPlatforms = numPlatformsArray[0];

        cl_platform_id[] platforms = new cl_platform_id[numPlatforms];
        CL.clGetPlatformIDs(platforms.length, platforms, null);
        cl_platform_id platform = platforms[platformIndex];

        cl_context_properties contextProperties = new cl_context_properties();
        contextProperties.addProperty(CL.CL_CONTEXT_PLATFORM, platform);

        int[] numDevicesArray = new int[1];
        CL.clGetDeviceIDs(platform, deviceType, 0, null, numDevicesArray);
        int numDevices = numDevicesArray[0];

        cl_device_id[] devices = new cl_device_id[numDevices];
        CL.clGetDeviceIDs(platform, deviceType, numDevices, devices, null);
        cl_device_id device = devices[deviceIndex];

        cl_context context = CL.clCreateContext(
                contextProperties, 1, new cl_device_id[]{device},
                null, null, null
        );

        cl_queue_properties properties = new cl_queue_properties();
        cl_command_queue commandQueue = CL.clCreateCommandQueueWithProperties(context, device, properties, null);

        /// ALLOC MEM

        cl_mem memA = CL.clCreateBuffer(context,
                CL.CL_MEM_READ_ONLY | CL.CL_MEM_COPY_HOST_PTR,
                Sizeof.cl_uchar * N * W, ap, null);
        cl_mem memDst = CL.clCreateBuffer(context,
                CL.CL_MEM_READ_WRITE,
                Sizeof.cl_uchar * N * W, null, null);

        AtomicReference<cl_mem> memARef = new AtomicReference<>(memA);


        // CREATE PROGRAM

        cl_program program = CL.clCreateProgramWithSource(context, 1, new String[]{src}, null, null);
        CL.clBuildProgram(program, 0, null, null, null, null);
        cl_kernel kernel = CL.clCreateKernel(program, "sampleKernel", null);

        int acc = 0;
        CL.clSetKernelArg(kernel, acc++, Sizeof.cl_mem, Pointer.to(memA));
        CL.clSetKernelArg(kernel, acc, Sizeof.cl_mem, Pointer.to(memDst));

        long[] globalWorkSize = new long[]{N, W};
        long[] localWorkSize = new long[]{16, 16};

        AtomicBoolean stop = new AtomicBoolean(false);

        // RUN PROGRAM
        long lastMs = System.currentTimeMillis();
        long timePerFrame = 0;
        ArrayList<Double> computeTimes = new ArrayList<>();
        // what the fuck am I doing here?
        // computeTimes.clone() - clone to prevent concurrent modification exception
        // ((ArrayList<Double>)clone) - cast because java jank
        // .stream() - convert to stream for efficiency
        // .mapToDouble(d->d) - I needed a DoubleStream not a Stream<Double>
        // .average() - take the average of the times
        // .ifPresent(d -> {}) - if the list is empty (it WON'T be) this never gets run
        // sout(format(...)) - formatting the decimal into a nice number in nanoseconds for fanciness
        @SuppressWarnings("unchecked") Thread shutdownThread = new Thread(() -> ((ArrayList<Double>)computeTimes.clone()).stream().mapToDouble(d->d).average().ifPresent(d -> System.out.println(String.format("Compute times: %.2f", d) + "ns")));
        Runtime.getRuntime().addShutdownHook(shutdownThread);
        ArrayList<Double> renderTimes = new ArrayList<>();
        @SuppressWarnings("unchecked") Thread shutdownThread2 = new Thread(() -> ((ArrayList<Double>)renderTimes.clone()).stream().mapToDouble(d->d).average().ifPresent(d -> System.out.println(String.format("Render times: %.2f", d) + "ns")));
        Runtime.getRuntime().addShutdownHook(shutdownThread2);
        Thread memSafetyShutdownThread = new Thread(() -> {
            stop.set(true);
            CL.clReleaseMemObject(memARef.get());
            CL.clReleaseMemObject(memDst);
            CL.clReleaseKernel(kernel);
            CL.clReleaseProgram(program);
            CL.clReleaseCommandQueue(commandQueue);
            CL.clReleaseContext(context);
        });
        Runtime.getRuntime().addShutdownHook(memSafetyShutdownThread);

        while (!stop.get()) {
            long start = System.nanoTime();
            cl_event compute = new cl_event();
            CL.clEnqueueNDRangeKernel(commandQueue, kernel, 2, null, globalWorkSize, localWorkSize, 0, null, compute);
            CL.clWaitForEvents(1, new cl_event[]{compute});
            long end = System.nanoTime();
            double t = (double) (end - start);
            System.out.println(t);
            computeTimes.add(t);

            start = System.nanoTime();
            cl_event read = new cl_event();
            CL.clEnqueueReadBuffer(commandQueue, memDst, CL.CL_TRUE, 0, N * W * Sizeof.cl_uchar, dstp, 0, null, read);
            CL.clWaitForEvents(1, new cl_event[]{read});

            drawer.setFramebuffer(dst.clone());
            end = System.nanoTime();
            renderTimes.add((double) (end - start));

            System.arraycopy(dst, 0, a, 0, N * W);

            CL.clReleaseMemObject(memARef.get());
            memARef.set(CL.clCreateBuffer(context,
                    CL.CL_MEM_READ_ONLY | CL.CL_MEM_COPY_HOST_PTR,
                    Sizeof.cl_uchar * N * W, ap, null));
            CL.clSetKernelArg(kernel, acc - 1, Sizeof.cl_mem, Pointer.to(memARef.get()));

            long diff = lastMs - System.currentTimeMillis();
            if (diff < timePerFrame) //noinspection BusyWait
                Thread.sleep(timePerFrame - diff);
            lastMs = System.currentTimeMillis();
        }
    }
}