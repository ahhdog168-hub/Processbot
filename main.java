import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

@SpringBootApplication
@RestController
@RequestMapping("/api")
public class VideoProcessorApplication {

    private static final String TEMP_DIR = "temp_videos";
    private static final ExecutorService PROCESSING_POOL = Executors.newFixedThreadPool(4); // 4 concurrent processes
    private static final Random RANDOM = new Random();

    public static void main(String[] args) {
        SpringApplication.run(VideoProcessorApplication.class, args);
    }

    @PostMapping("/process")
    public void processVideo(
            @RequestParam("file") MultipartFile file,
            @RequestParam("index") int index,
            @RequestParam("speed") float speed,
            @RequestParam("pitch") float pitch,
            @RequestParam("watermark") boolean watermark,
            @RequestParam("filters") boolean filters,
            @RequestParam("aspect") boolean aspect,
            @RequestParam("randomCuts") boolean randomCuts,
            @RequestParam("audioMix") boolean audioMix,
            @RequestParam("aiDetection") boolean aiDetection,
            @RequestParam("autoEdit") boolean autoEdit,
            @RequestParam("contentAnalysis") boolean contentAnalysis,
            HttpServletResponse response) throws IOException {

        // Create temp directory if not exists
        Path tempDir = Paths.get(TEMP_DIR);
        if (!Files.exists(tempDir)) {
            Files.createDirectories(tempDir);
        }

        // Generate unique filenames
        String inputFilename = "input_" + index + "_" + UUID.randomUUID() + getFileExtension(file.getOriginalFilename());
        String outputFilename = "output_" + index + "_" + UUID.randomUUID() + ".mp4";
        
        Path inputPath = tempDir.resolve(inputFilename);
        Path outputPath = tempDir.resolve(outputFilename);

        try {
            // Save uploaded file
            file.transferTo(inputPath.toFile());

            // Build FFmpeg command with AI-powered transformations
            String ffmpegCommand = buildAIEnhancedCommand(
                inputPath.toString(), 
                outputPath.toString(),
                speed, 
                pitch,
                watermark,
                filters,
                aspect,
                randomCuts,
                audioMix,
                aiDetection,
                autoEdit,
                contentAnalysis
            );

            // Execute FFmpeg command with timeout
            boolean success = executeCommandWithTimeout(ffmpegCommand, 300); // 5 minute timeout

            if (!success) {
                response.sendError(500, "Video processing timed out");
                return;
            }

            // Verify output file was created
            if (!Files.exists(outputPath) {
                response.sendError(500, "Output file was not created");
                return;
            }

            // Stream the processed file back
            response.setContentType("video/mp4");
            response.setHeader("Content-Disposition", "attachment; filename=processed_" + file.getOriginalFilename());
            Files.copy(outputPath, response.getOutputStream());
            response.flushBuffer();

        } finally {
            // Clean up temp files in background
            PROCESSING_POOL.submit(() -> {
                try {
                    Files.deleteIfExists(inputPath);
                    Files.deleteIfExists(outputPath);
                } catch (IOException e) {
                    System.err.println("Error cleaning up temp files: " + e.getMessage());
                }
            });
        }
    }

    private String buildAIEnhancedCommand(
            String inputPath, String outputPath,
            float speed, float pitch,
            boolean watermark, boolean filters, boolean aspect,
            boolean randomCuts, boolean audioMix,
            boolean aiDetection, boolean autoEdit, boolean contentAnalysis) {

        StringBuilder cmd = new StringBuilder("ffmpeg -i ")
            .append(escapePath(inputPath));

        // Base video filter chain
        List<String> videoFilters = new ArrayList<>();
        
        // Speed adjustment
        videoFilters.add("setpts=" + (1/speed) + "*PTS");
        
        // AI-powered transformations
        if (aiDetection || autoEdit || contentAnalysis) {
            // Simulate AI processing with random modifications
            videoFilters.add("crop=iw-" + RANDOM.nextInt(50) + ":ih-" + RANDOM.nextInt(50));
            videoFilters.add("hue=h=" + (RANDOM.nextInt(20) - 10) + ":s=" + (0.9 + RANDOM.nextFloat() * 0.2));
            
            if (randomCuts) {
                // Simulate random cuts by selecting segments
                cmd.insert(0, "ffmpeg -ss " + RANDOM.nextInt(5) + " -t " + (10 + RANDOM.nextInt(30)) + " -i ");
            }
        }
        
        // Visual filters
        if (filters) {
            videoFilters.add("colorchannelmixer=rr=" + (0.8 + RANDOM.nextFloat() * 0.4) + 
                           ":gg=" + (0.8 + RANDOM.nextFloat() * 0.4) + 
                           ":bb=" + (0.8 + RANDOM.nextFloat() * 0.4));
            videoFilters.add("eq=contrast=" + (1.0 + RANDOM.nextFloat() * 0.3) + 
                           ":brightness=" + (RANDOM.nextFloat() * 0.1 - 0.05));
        }
        
        // Watermark
        if (watermark) {
            videoFilters.add("drawtext=text='UserContent':x=" + (10 + RANDOM.nextInt(50)) + 
                           ":y=h-th-" + (10 + RANDOM.nextInt(50)) + 
                           ":fontsize=" + (20 + RANDOM.nextInt(15)) + 
                           ":fontcolor=white@0." + (3 + RANDOM.nextInt(4)));
        }
        
        // Aspect ratio changes
        if (aspect) {
            videoFilters.add("scale=iw:" + (int)(ih * (0.8 + RANDOM.nextFloat() * 0.4)));
        }
        
        // Audio filters
        List<String> audioFilters = new ArrayList<>();
        audioFilters.add("atempo=" + speed);
        audioFilters.add("asetrate=44100*" + pitch);
        
        if (audioMix) {
            audioFilters.add("aecho=0.8:0.9:" + (500 + RANDOM.nextInt(1000)) + 
                            ":" + (0.4 + RANDOM.nextFloat() * 0.3));
        }
        
        // Combine filters
        if (!videoFilters.isEmpty()) {
            cmd.append(" -vf \"").append(String.join(",", videoFilters)).append("\"");
        }
        
        if (!audioFilters.isEmpty()) {
            cmd.append(" -af \"").append(String.join(",", audioFilters)).append("\"");
        }
        
        // Output settings
        cmd.append(" -c:v libx264 -preset fast -crf 23 -c:a aac -strict experimental ")
           .append(escapePath(outputPath));
        
        return cmd.toString();
    }

    private boolean executeCommandWithTimeout(String command, int timeoutSeconds) {
        try {
            Process process = Runtime.getRuntime().exec(command);
            
            Future<?> future = PROCESSING_POOL.submit(() -> {
                try {
                    // Read error stream to prevent process hanging
                    try (BufferedReader errorReader = new BufferedReader(
                            new InputStreamReader(process.getErrorStream()))) {
                        String line;
                        while ((line = errorReader.readLine()) != null) {
                            System.out.println("[FFmpeg] " + line);
                        }
                    }
                    
                    return process.waitFor();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return -1;
                } catch (IOException e) {
                    return -1;
                }
            });
            
            try {
                future.get(timeoutSeconds, TimeUnit.SECONDS);
                return process.exitValue() == 0;
            } catch (TimeoutException e) {
                process.destroy();
                future.cancel(true);
                return false;
            } catch (Exception e) {
                return false;
            }
        } catch (IOException e) {
            return false;
        }
    }

    private String escapePath(String path) {
        return "\"" + path + "\"";
    }

    private String getFileExtension(String filename) {
        return filename != null ? filename.substring(filename.lastIndexOf(".")) : ".mp4";
    }
}
