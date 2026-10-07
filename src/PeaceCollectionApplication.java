import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PeaceCollectionApplication {
    private static final Path SITE = Path.of("web").toAbsolutePath().normalize();
    private static final Path DATA = Path.of(System.getenv().getOrDefault(
            "PEACECOLLECTION_DATA_DIR", "peacecollection-data")).toAbsolutePath().normalize();
    private static final int MAX_REQUEST_BYTES = 16_384;
    private static final long SESSION_MILLIS = 8 * 60 * 60 * 1000L;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Map<String, Session> SESSIONS = new ConcurrentHashMap<>();
    private static final Map<String, LoginAttempts> LOGIN_ATTEMPTS = new ConcurrentHashMap<>();
    private static final List<Product> PRODUCTS = new ArrayList<>();
    private static final List<CustomerRequest> CUSTOMER_REQUESTS = new ArrayList<>();
    private static final Map<String, String> CONTENT = new HashMap<>();
    private static final Object DATA_LOCK = new Object();
    private static char[] adminPassword;
    private static byte[] passwordSalt;
    private static byte[] passwordHash;

    private PeaceCollectionApplication() {
    }

    public static void main(String[] args) throws IOException {
        configureAdminPassword();
        loadData();

        String configuredPort = System.getenv().getOrDefault("PORT",
                System.getenv().getOrDefault("PEACECOLLECTION_PORT", "8080"));
        int port = Integer.parseInt(configuredPort);
        String host = System.getenv().getOrDefault("PEACECOLLECTION_HOST", "0.0.0.0");
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName(host), port), 0);
        server.createContext("/healthz", PeaceCollectionApplication::handleHealth);
        server.createContext("/robots.txt", PeaceCollectionApplication::handleRobots);
        server.createContext("/sitemap.xml", PeaceCollectionApplication::handleSitemap);
        server.createContext("/api/order", PeaceCollectionApplication::handleOrder);
        server.createContext("/api/products", PeaceCollectionApplication::handleProducts);
        server.createContext("/api/content", PeaceCollectionApplication::handleContent);
        server.createContext("/api/admin/", PeaceCollectionApplication::handleAdmin);
        server.createContext("/", PeaceCollectionApplication::serveWebsite);
        server.start();
        System.out.println("PeaceCollection — Botigou Dera Fashions is running at http://" + host + ":" + port);
    }

    private static void handleHealth(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, "text/plain; charset=UTF-8", "Method not allowed");
            return;
        }
        send(exchange, 200, "text/plain; charset=UTF-8", "ok");
    }

    private static void handleRobots(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, "text/plain; charset=UTF-8", "Method not allowed");
            return;
        }
        String baseUrl = publicBaseUrl();
        String body = "User-agent: *\nAllow: /\nDisallow: /admin\nDisallow: /api/\n"
                + (baseUrl == null ? "" : "Sitemap: " + baseUrl + "/sitemap.xml\n");
        send(exchange, 200, "text/plain; charset=UTF-8", body);
    }

    private static void handleSitemap(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, "text/plain; charset=UTF-8", "Method not allowed");
            return;
        }
        String baseUrl = publicBaseUrl();
        if (baseUrl == null) {
            send(exchange, 503, "text/plain; charset=UTF-8",
                    "Set PEACECOLLECTION_PUBLIC_URL or deploy on Render to publish the sitemap.");
            return;
        }
        String body = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">\n"
                + "  <url><loc>" + baseUrl + "/</loc></url>\n"
                + "</urlset>\n";
        send(exchange, 200, "application/xml; charset=UTF-8", body);
    }

    private static String publicBaseUrl() {
        String configured = System.getenv("PEACECOLLECTION_PUBLIC_URL");
        if (configured == null || configured.isBlank()) {
            configured = System.getenv("RENDER_EXTERNAL_URL");
        }
        if (configured == null || configured.isBlank()) {
            String hostname = System.getenv("RENDER_EXTERNAL_HOSTNAME");
            if (hostname != null && !hostname.isBlank()) configured = "https://" + hostname;
        }
        if (configured == null || configured.isBlank()) return null;

        try {
            java.net.URI uri = java.net.URI.create(configured.trim());
            String path = uri.getRawPath();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || (path != null && !path.isEmpty() && !"/".equals(path))
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalStateException("PEACECOLLECTION_PUBLIC_URL must be an HTTPS origin without a path, query, or fragment.");
            }
            return "https://" + uri.getRawAuthority();
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("PEACECOLLECTION_PUBLIC_URL is not a valid HTTPS origin.", exception);
        }
    }

    private static void configureAdminPassword() {
        String configured = System.getenv("PEACECOLLECTION_ADMIN_PASSWORD");
        if (configured == null || configured.length() < 12) {
            throw new IllegalStateException("Set PEACECOLLECTION_ADMIN_PASSWORD to a password of at least 12 characters before starting the server.");
        }
        adminPassword = configured.toCharArray();
        passwordSalt = new byte[16];
        RANDOM.nextBytes(passwordSalt);
        passwordHash = hashPassword(adminPassword, passwordSalt);
    }

    private static byte[] hashPassword(char[] password, byte[] salt) {
        PBEKeySpec specification = new PBEKeySpec(password, salt, 210_000, 256);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(specification).getEncoded();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Secure password hashing is unavailable.", exception);
        } finally {
            specification.clearPassword();
        }
    }

    private static void loadData() throws IOException {
        Files.createDirectories(DATA);
        synchronized (DATA_LOCK) {
            Path productFile = DATA.resolve("products.tsv");
            if (Files.exists(productFile)) {
                for (String line : Files.readAllLines(productFile, StandardCharsets.UTF_8)) {
                    List<String> fields = decodeFields(line);
                    if (fields.size() == 7) {
                        PRODUCTS.add(new Product(fields.get(0), fields.get(1), fields.get(2),
                                fields.get(3), fields.get(4), fields.get(5), fields.get(6)));
                    }
                }
            } else {
                seedProducts();
                saveProducts();
            }
            migrateCatalog();

            Path contentFile = DATA.resolve("content.tsv");
            if (Files.exists(contentFile)) {
                for (String line : Files.readAllLines(contentFile, StandardCharsets.UTF_8)) {
                    List<String> fields = decodeFields(line);
                    if (fields.size() == 2) {
                        CONTENT.put(fields.get(0), fields.get(1));
                    }
                }
            } else {
                CONTENT.put("announcement", "A little colour goes a long way ✳ Find your feel-good fit");
                CONTENT.put("heroDescription", "Feel-good fashion, made for wherever the day takes you. Find the colour, cut and little detail that feels like you.");
                CONTENT.put("storyFirst", "We’re here for the outfit that lifts your mood, the colour you can’t stop thinking about, and the confidence that comes from showing up as yourself.");
                CONTENT.put("storySecond", "Explore pieces to mix, match and make your own. Your style is the story — we’re just happy to be a part of it.");
                saveContent();
            }

            Path requestFile = DATA.resolve("requests.tsv");
            if (Files.exists(requestFile)) {
                for (String line : Files.readAllLines(requestFile, StandardCharsets.UTF_8)) {
                    List<String> fields = decodeFields(line);
                    if (fields.size() == 7) {
                        CUSTOMER_REQUESTS.add(new CustomerRequest(fields.get(0), fields.get(1), fields.get(2),
                                fields.get(3), fields.get(4), fields.get(5), fields.get(6)));
                    }
                }
            }
        }
    }

    private static void seedProducts() {
        PRODUCTS.add(new Product("1", "African Print Dera", "Full-length print · Deep brown", "84", "brown", "print", "texture-african.jpg"));
        PRODUCTS.add(new Product("2", "Heritage Print Dera", "Full-length print · Deep brown", "96", "brown", "print", "texture-african.jpg"));
        PRODUCTS.add(new Product("3", "Gold Print Dera", "Yellow and white African print", "68", "dark-yellow", "print", "texture-african.jpg"));
        PRODUCTS.add(new Product("4", "Rose Dera Kaftan", "Flowing embroidered kaftan · Deep pink", "92", "dark-pink", "kaftan embroidered", "texture-african.jpg"));
        PRODUCTS.add(new Product("5", "Brown Print Dera", "African print · Earthy brown", "88", "brown", "print", "texture-african.jpg"));
        PRODUCTS.add(new Product("6", "Golden Hour Dera", "Soft drape · Dark yellow", "42", "dark-yellow", "kaftan", "texture-african.jpg"));
        PRODUCTS.add(new Product("7", "Classic Black Dera", "Modelled look · Flowing fit", "78", "black", "kaftan", "texture-african.jpg"));
        PRODUCTS.add(new Product("8", "Earth Tone Dera", "Modelled print · Warm brown", "82", "brown", "print", "texture-african.jpg"));
        PRODUCTS.add(new Product("9", "Golden Hour Dera", "Modelled look · Dark yellow", "86", "dark-yellow", "kaftan", "texture-african.jpg"));
        PRODUCTS.add(new Product("10", "Rose Glow Dera", "Modelled print · Deep pink", "80", "dark-pink", "kaftan embroidered", "texture-african.jpg"));
        PRODUCTS.add(new Product("11", "Ivory Day Dera", "Modelled look · Fresh white", "76", "white", "print", "texture-african.jpg"));
        PRODUCTS.add(new Product("12", "Ankara Heritage Dera", "Full-length African print · Deep brown", "88", "brown", "print", "texture-african.jpg"));
    }

    private static void migrateCatalog() throws IOException {
        Path versionFile = DATA.resolve("catalog-version");
        int version = Files.exists(versionFile)
                ? Integer.parseInt(Files.readString(versionFile, StandardCharsets.UTF_8).trim()) : 1;
        if (version < 2) {
            addProductIfMissing(new Product("backless-black", "Backless Black Dera", "Open-back flowing style · Black", "95", "black", "backless", "texture-african.jpg"));
            addProductIfMissing(new Product("backless-rose", "Backless Rose Dera", "Open-back flowing style · Deep pink", "95", "dark-pink", "backless", "texture-african.jpg"));
            addProductIfMissing(new Product("backless-brown", "Backless Brown Dera", "Open-back flowing style · Earth brown", "95", "brown", "backless", "texture-african.jpg"));
            addProductIfMissing(new Product("elegance-white", "Elegance White Dera", "Elegant flowing silhouette · White", "98", "white", "elegance", "texture-african.jpg"));
            addProductIfMissing(new Product("elegance-gold", "Elegance Gold Dera", "Elegant flowing silhouette · Dark yellow", "98", "dark-yellow", "elegance", "texture-african.jpg"));
            addProductIfMissing(new Product("elegance-brown", "Elegance Brown Dera", "Elegant flowing silhouette · Earth brown", "98", "brown", "elegance", "texture-african.jpg"));
            saveProducts();
            Files.writeString(versionFile, "2", StandardCharsets.UTF_8);
        }
        if (version < 3) {
            addProductIfMissing(new Product("print-sunrise", "Sunrise Print Dera", "Bright African print · Dark yellow", "74", "dark-yellow", "print", "texture-african.jpg"));
            addProductIfMissing(new Product("blossom-kaftan", "Blossom Embroidered Dera", "Embroidered flowing kaftan · White", "104", "white", "kaftan embroidered", "texture-african.jpg"));
            addProductIfMissing(new Product("backless-plum", "Plum Backless Dera", "Open-back evening style · Deep pink", "99", "dark-pink", "backless elegance", "texture-african.jpg"));
            addProductIfMissing(new Product("midnight-grace", "Midnight Grace Dera", "Embroidered kaftan · Black", "102", "black", "kaftan embroidered", "texture-african.jpg"));
            addProductIfMissing(new Product("cocoa-print", "Cocoa Heritage Dera", "Full-length African print · Cocoa brown", "91", "brown", "print elegance", "texture-african.jpg"));
            addProductIfMissing(new Product("golden-elegance", "Golden Elegance Dera", "Elegant embroidered silhouette · Dark yellow", "108", "dark-yellow", "elegance embroidered", "texture-african.jpg"));
            addProductIfMissing(new Product("backless-ivory", "Ivory Backless Dera", "Open-back flowing style · Ivory white", "99", "white", "backless", "texture-african.jpg"));
            addProductIfMissing(new Product("rose-elegance", "Rose Elegance Dera", "Elegant flowing silhouette · Deep pink", "108", "dark-pink", "elegance embroidered", "texture-african.jpg"));
            addProductIfMissing(new Product("black-ankara", "Black Ankara Dera", "Bold African print · Black", "93", "black", "print", "texture-african.jpg"));
            addProductIfMissing(new Product("earth-kaftan", "Earthtone Embroidered Kaftan", "Soft embroidered drape · Brown", "101", "brown", "kaftan embroidered", "texture-african.jpg"));
            addProductIfMissing(new Product("backless-saffron", "Saffron Backless Dera", "Open-back celebration style · Dark yellow", "99", "dark-yellow", "backless", "texture-african.jpg"));
            addProductIfMissing(new Product("ivory-elegance", "Ivory Elegance Dera", "Elegant full-length silhouette · White", "108", "white", "elegance", "texture-african.jpg"));
            saveProducts();
            Files.writeString(versionFile, "3", StandardCharsets.UTF_8);
        }
        if (version < 4) {
            addProductIfMissing(new Product("boubou-midnight", "Midnight Boubou Dera", "Relaxed flowing boubou · Black", "112", "black", "boubou", "texture-african.jpg"));
            addProductIfMissing(new Product("boubou-earth", "Earth Boubou Dera", "Relaxed flowing boubou · Brown", "112", "brown", "boubou embroidered", "texture-african.jpg"));
            addProductIfMissing(new Product("wrap-rose", "Rose Wrap Dera", "Wrap-front flowing style · Deep pink", "106", "dark-pink", "wrap elegance", "texture-african.jpg"));
            addProductIfMissing(new Product("wrap-ivory", "Ivory Wrap Dera", "Wrap-front flowing style · White", "106", "white", "wrap", "texture-african.jpg"));
            addProductIfMissing(new Product("maxi-heritage", "Heritage Maxi Dera", "Full-length flowing print · Brown", "110", "brown", "maxi print", "texture-african.jpg"));
            addProductIfMissing(new Product("maxi-sunrise", "Sunrise Maxi Dera", "Full-length flowing print · Dark yellow", "110", "dark-yellow", "maxi print", "texture-african.jpg"));
            addProductIfMissing(new Product("flare-rose", "Rose Flare-Sleeve Dera", "Wide sleeve flowing style · Deep pink", "114", "dark-pink", "flare elegance", "texture-african.jpg"));
            addProductIfMissing(new Product("flare-black", "Black Flare-Sleeve Dera", "Wide sleeve flowing style · Black", "114", "black", "flare embroidered", "texture-african.jpg"));
            addProductIfMissing(new Product("occasion-ivory", "Ivory Occasion Dera", "Special occasion flowing style · White", "118", "white", "occasion elegance", "texture-african.jpg"));
            addProductIfMissing(new Product("occasion-gold", "Golden Occasion Dera", "Special occasion flowing style · Dark yellow", "118", "dark-yellow", "occasion embroidered", "texture-african.jpg"));
            saveProducts();
            Files.writeString(versionFile, "4", StandardCharsets.UTF_8);
        }
        if (version < 5) {
            replaceProduct("elegance-gold", new Product("elegance-gold", "Golden Backless Dera",
                    "Open-back flowing style · Dark yellow", "98", "dark-yellow", "backless", "texture-african.jpg"));
            replaceProduct("print-sunrise", new Product("print-sunrise", "Pink Backless Dera",
                    "Open-back flowing style · Deep pink", "74", "dark-pink", "backless", "texture-african.jpg"));
            saveProducts();
            Files.writeString(versionFile, "5", StandardCharsets.UTF_8);
        }
        if (version < 6) {
            replaceProduct("elegance-gold", new Product("elegance-gold", "Golden Mix Dera",
                    "Mixed print · Gold, black and white", "98", "dark-yellow", "mixed", "texture-african.jpg"));
            replaceProduct("print-sunrise", new Product("print-sunrise", "Rose Mix Dera",
                    "Mixed print · Pink, turquoise and black", "74", "dark-pink", "mixed", "texture-african.jpg"));
            saveProducts();
            Files.writeString(versionFile, "6", StandardCharsets.UTF_8);
        }
        if (version < 7) {
            addProductIfMissing(new Product("elegance-black", "Midnight Elegance Dera", "Elegant flowing silhouette · Black", "108", "black", "elegance", "texture-african.jpg"));
            addProductIfMissing(new Product("boubou-yellow", "Golden Boubou Dera", "Relaxed flowing boubou · Dark yellow", "112", "dark-yellow", "boubou", "texture-african.jpg"));
            addProductIfMissing(new Product("boubou-pink", "Rose Boubou Dera", "Relaxed flowing boubou · Deep pink", "112", "dark-pink", "boubou", "texture-african.jpg"));
            addProductIfMissing(new Product("boubou-white", "Ivory Boubou Dera", "Relaxed flowing boubou · White", "112", "white", "boubou", "texture-african.jpg"));
            addProductIfMissing(new Product("wrap-black", "Midnight Wrap Dera", "Wrap-front flowing style · Black", "106", "black", "wrap", "texture-african.jpg"));
            addProductIfMissing(new Product("wrap-brown", "Cocoa Wrap Dera", "Wrap-front flowing style · Brown", "106", "brown", "wrap", "texture-african.jpg"));
            addProductIfMissing(new Product("wrap-yellow", "Saffron Wrap Dera", "Wrap-front flowing style · Dark yellow", "106", "dark-yellow", "wrap", "texture-african.jpg"));
            addProductIfMissing(new Product("maxi-black", "Midnight Maxi Dera", "Full-length flowing style · Black", "110", "black", "maxi", "texture-african.jpg"));
            addProductIfMissing(new Product("maxi-pink", "Rose Maxi Dera", "Full-length flowing style · Deep pink", "110", "dark-pink", "maxi", "texture-african.jpg"));
            addProductIfMissing(new Product("maxi-white", "Ivory Maxi Dera", "Full-length flowing style · White", "110", "white", "maxi", "texture-african.jpg"));
            addProductIfMissing(new Product("flare-brown", "Cocoa Flare-Sleeve Dera", "Wide sleeve flowing style · Brown", "114", "brown", "flare", "texture-african.jpg"));
            addProductIfMissing(new Product("flare-yellow", "Golden Flare-Sleeve Dera", "Wide sleeve flowing style · Dark yellow", "114", "dark-yellow", "flare", "texture-african.jpg"));
            addProductIfMissing(new Product("flare-white", "Ivory Flare-Sleeve Dera", "Wide sleeve flowing style · White", "114", "white", "flare", "texture-african.jpg"));
            addProductIfMissing(new Product("occasion-black", "Midnight Occasion Dera", "Special occasion flowing style · Black", "118", "black", "occasion", "texture-african.jpg"));
            addProductIfMissing(new Product("occasion-brown", "Cocoa Occasion Dera", "Special occasion flowing style · Brown", "118", "brown", "occasion", "texture-african.jpg"));
            addProductIfMissing(new Product("occasion-pink", "Rose Occasion Dera", "Special occasion flowing style · Deep pink", "118", "dark-pink", "occasion", "texture-african.jpg"));
            addProductIfMissing(new Product("mixed-black", "Midnight Mix Dera", "Mixed print · Black, gold and white", "98", "black", "mixed", "texture-african.jpg"));
            addProductIfMissing(new Product("mixed-brown", "Cocoa Mix Dera", "Mixed print · Brown, rose and gold", "98", "brown", "mixed", "texture-african.jpg"));
            addProductIfMissing(new Product("mixed-white", "Ivory Mix Dera", "Mixed print · White, pink and gold", "98", "white", "mixed", "texture-african.jpg"));
            addProductIfMissing(new Product("kaftan-brown", "Cocoa Dera Kaftan", "Flowing kaftan · Earth brown", "92", "brown", "kaftan", "texture-african.jpg"));
            addProductIfMissing(new Product("kaftan-white", "Ivory Dera Kaftan", "Flowing kaftan · White", "92", "white", "kaftan", "texture-african.jpg"));
            addProductIfMissing(new Product("print-pink", "Rose Print Dera", "African print · Deep pink", "88", "dark-pink", "print", "texture-african.jpg"));
            saveProducts();
            Files.writeString(versionFile, "7", StandardCharsets.UTF_8);
        }
        if (version < 8) {
            replaceProduct("1", new Product("1", "Decorated Backless African Print Dera",
                    "Open-back Ankara print with decorative detailing · Deep brown", "94",
                    "brown", "print backless embroidered", "texture-african.jpg"));
            saveProducts();
            Files.writeString(versionFile, "8", StandardCharsets.UTF_8);
        }
    }

    private static void addProductIfMissing(Product product) {
        if (PRODUCTS.stream().noneMatch(existing -> existing.id.equals(product.id))) {
            PRODUCTS.add(product);
        }
    }

    private static void replaceProduct(String id, Product replacement) {
        for (int i = 0; i < PRODUCTS.size(); i++) {
            if (PRODUCTS.get(i).id.equals(id)) {
                PRODUCTS.set(i, replacement);
                return;
            }
        }
    }

    private static void serveWebsite(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            send(exchange, 405, "text/plain; charset=UTF-8", "Method not allowed");
            return;
        }
        String requested = exchange.getRequestURI().getPath();
        String fileName = "/".equals(requested) ? "index.html" : requested.substring(1);
        if ("admin".equals(fileName) || "admin/".equals(fileName)) {
            fileName = "admin.html";
        }
        Path file = SITE.resolve(fileName).normalize();
        if (!file.startsWith(SITE) || !Files.isRegularFile(file)) {
            send(exchange, 404, "text/plain; charset=UTF-8", "Page not found");
            return;
        }
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        byte[] content = Files.readAllBytes(file);
        if ("index.html".equals(fileName)) {
            String html = new String(content, StandardCharsets.UTF_8);
            String baseUrl = publicBaseUrl();
            if (baseUrl == null) {
                html = html.replace("  <link rel=\"canonical\" href=\"__PEACECOLLECTION_PUBLIC_URL__/\">\n", "")
                        .replace("  <meta property=\"og:url\" content=\"__PEACECOLLECTION_PUBLIC_URL__/\">\n", "");
            } else {
                html = html.replace("__PEACECOLLECTION_PUBLIC_URL__", baseUrl);
            }
            content = html.getBytes(StandardCharsets.UTF_8);
        }
        send(exchange, 200, contentType(file), content);
    }

    private static String contentType(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        if (name.endsWith(".css")) return "text/css; charset=UTF-8";
        if (name.endsWith(".js")) return "text/javascript; charset=UTF-8";
        if (name.endsWith(".html")) return "text/html; charset=UTF-8";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".webp")) return "image/webp";
        return "application/octet-stream";
    }

    private static void handleProducts(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "Method not allowed");
            return;
        }
        List<Product> snapshot;
        synchronized (DATA_LOCK) {
            snapshot = new ArrayList<>(PRODUCTS);
        }
        List<String> json = new ArrayList<>();
        for (Product product : snapshot) json.add(product.toJson());
        sendJson(exchange, 200, "[" + String.join(",", json) + "]");
    }

    private static void handleContent(HttpExchange exchange) throws IOException {
        if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "Method not allowed");
            return;
        }
        synchronized (DATA_LOCK) {
            sendJson(exchange, 200, mapToJson(CONTENT));
        }
    }

    private static void handleOrder(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "Method not allowed");
            return;
        }
        Map<String, String> fields = readForm(exchange);
        if (fields == null) return;
        String customer = fields.getOrDefault("customer", "").trim();
        String email = fields.getOrDefault("email", "").trim();
        String items = fields.getOrDefault("items", "").trim();
        String finish = fields.getOrDefault("finish", "").trim();
        if (customer.isEmpty() || customer.length() > 100
                || email.length() > 254 || !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
                || items.isEmpty() || items.length() > 2000
                || finish.length() > 100) {
            sendJson(exchange, 400, "{\"message\":\"Please provide a name, valid email, and styling request\"}");
            return;
        }
        synchronized (DATA_LOCK) {
            CustomerRequest request = new CustomerRequest(UUID.randomUUID().toString(), customer, email,
                    items, finish.isEmpty() ? "Help me pick an outfit" : finish,
                    Instant.now().toString(), "new");
            CUSTOMER_REQUESTS.add(request);
            try {
                saveRequests();
            } catch (IOException exception) {
                CUSTOMER_REQUESTS.remove(request);
                sendJson(exchange, 500, "{\"message\":\"We could not save your styling request. Please try again later.\"}");
                return;
            }
        }
        sendJson(exchange, 200, "{\"message\":\"Thanks! Your styling request has been received.\"}");
    }

    private static void handleAdmin(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if ("/api/admin/login".equals(path)) {
            handleLogin(exchange);
            return;
        }
        if ("GET".equalsIgnoreCase(exchange.getRequestMethod()) && "/api/admin/session".equals(path)) {
            Session session = authenticatedSession(exchange);
            if (session == null) {
                sendJson(exchange, 401, "{\"message\":\"Please sign in again.\"}");
                return;
            }
            sendJson(exchange, 200, "{\"csrf\":\"" + jsonEscape(session.csrf) + "\"}");
            return;
        }
        Session session = authenticatedSession(exchange);
        if (session == null) {
            sendJson(exchange, 401, "{\"message\":\"Your owner session has expired. Please sign in again.\"}");
            return;
        }
        boolean readingRequests = "/api/admin/requests".equals(path)
                && "GET".equalsIgnoreCase(exchange.getRequestMethod());
        boolean readingImages = "/api/admin/images".equals(path)
                && "GET".equalsIgnoreCase(exchange.getRequestMethod());
        if (readingRequests) {
            handleAdminRequests(exchange);
            return;
        }
        if (readingImages) {
            handleAdminImages(exchange);
            return;
        }
        boolean deletingProduct = "/api/admin/products".equals(path)
                && "DELETE".equalsIgnoreCase(exchange.getRequestMethod());
        if ((!deletingProduct && !"POST".equalsIgnoreCase(exchange.getRequestMethod()))
                || !constantTimeEquals(session.csrf, exchange.getRequestHeaders().getFirst("X-CSRF-Token"))) {
            sendJson(exchange, 403, "{\"message\":\"This admin action could not be verified. Refresh the page and try again.\"}");
            return;
        }

        if ("/api/admin/logout".equals(path)) {
            SESSIONS.remove(session.id);
            exchange.getResponseHeaders().add("Set-Cookie", cookie("", 0));
            sendJson(exchange, 200, "{\"message\":\"Signed out.\"}");
        } else if ("/api/admin/products".equals(path)) {
            handleAdminProduct(exchange);
        } else if ("/api/admin/content".equals(path)) {
            handleAdminContent(exchange);
        } else if ("/api/admin/requests/status".equals(path)) {
            handleRequestStatus(exchange);
        } else if ("/api/admin/requests/delete".equals(path)) {
            handleRequestDelete(exchange);
        } else {
            sendJson(exchange, 404, "{\"message\":\"Admin endpoint not found.\"}");
        }
    }

    private static void handleLogin(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"message\":\"Method not allowed\"}");
            return;
        }
        String address = exchange.getRemoteAddress().getAddress().getHostAddress();
        LoginAttempts attempts = LOGIN_ATTEMPTS.computeIfAbsent(address, ignored -> new LoginAttempts());
        synchronized (attempts) {
            if (System.currentTimeMillis() < attempts.lockedUntil) {
                sendJson(exchange, 429, "{\"message\":\"Too many sign-in attempts. Please wait five minutes.\"}");
                return;
            }
        }
        Map<String, String> fields = readForm(exchange);
        if (fields == null) return;
        char[] provided = fields.getOrDefault("password", "").toCharArray();
        byte[] candidate = hashPassword(provided, passwordSalt);
        boolean valid = MessageDigest.isEqual(passwordHash, candidate);
        java.util.Arrays.fill(provided, '\0');
        if (!valid) {
            synchronized (attempts) {
                attempts.failures++;
                if (attempts.failures >= 5) {
                    attempts.failures = 0;
                    attempts.lockedUntil = System.currentTimeMillis() + 5 * 60 * 1000L;
                }
            }
            sendJson(exchange, 401, "{\"message\":\"That password was not accepted.\"}");
            return;
        }
        synchronized (attempts) {
            attempts.failures = 0;
            attempts.lockedUntil = 0;
        }
        String id = randomToken(32);
        Session session = new Session(id, randomToken(32), System.currentTimeMillis() + SESSION_MILLIS);
        SESSIONS.put(id, session);
        exchange.getResponseHeaders().add("Set-Cookie", cookie(id, (int) (SESSION_MILLIS / 1000)));
        sendJson(exchange, 200, "{\"csrf\":\"" + jsonEscape(session.csrf) + "\",\"message\":\"Welcome to your owner dashboard.\"}");
    }

    private static String cookie(String value, int maxAge) {
        String cookie = "PCSESSION=" + value + "; Path=/; HttpOnly; SameSite=Strict; Max-Age=" + maxAge;
        if ("true".equalsIgnoreCase(System.getenv("PEACECOLLECTION_SECURE_COOKIES"))) {
            cookie += "; Secure";
        }
        return cookie;
    }

    private static Session authenticatedSession(HttpExchange exchange) {
        String cookieHeader = exchange.getRequestHeaders().getFirst("Cookie");
        if (cookieHeader == null) return null;
        for (String part : cookieHeader.split(";")) {
            String[] value = part.trim().split("=", 2);
            if (value.length == 2 && "PCSESSION".equals(value[0])) {
                Session session = SESSIONS.get(value[1]);
                if (session != null && session.expiresAt > System.currentTimeMillis()) {
                    return session;
                }
                SESSIONS.remove(value[1]);
            }
        }
        return null;
    }

    private static void handleAdminProduct(HttpExchange exchange) throws IOException {
        if ("DELETE".equalsIgnoreCase(exchange.getRequestMethod())) {
            String id = queryParameter(exchange, "id");
            if (id.isBlank()) {
                sendJson(exchange, 400, "{\"message\":\"A product id is required.\"}");
                return;
            }
            synchronized (DATA_LOCK) {
                int index = -1;
                for (int i = 0; i < PRODUCTS.size(); i++) {
                    if (PRODUCTS.get(i).id.equals(id)) {
                        index = i;
                        break;
                    }
                }
                if (index < 0) {
                    sendJson(exchange, 404, "{\"message\":\"Product not found.\"}");
                    return;
                }
                Product removed = PRODUCTS.remove(index);
                try {
                    saveProducts();
                } catch (IOException exception) {
                    PRODUCTS.add(index, removed);
                    sendJson(exchange, 500, "{\"message\":\"Could not save the catalogue change. Please try again.\"}");
                    return;
                }
            }
            sendJson(exchange, 200, "{\"message\":\"Product removed.\"}");
            return;
        }
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"message\":\"Method not allowed\"}");
            return;
        }
        Map<String, String> fields = readForm(exchange);
        if (fields == null) return;
        String id = fields.getOrDefault("id", "").trim();
        String name = fields.getOrDefault("name", "").trim();
        String description = fields.getOrDefault("description", "").trim();
        String price = fields.getOrDefault("price", "").trim();
        String colour = fields.getOrDefault("colour", "").trim();
        String style = fields.getOrDefault("style", "").trim();
        String image = fields.getOrDefault("image", "").trim();
        if (name.isEmpty() || name.length() > 80 || description.isEmpty() || description.length() > 140
                || !price.matches("\\d{1,6}(\\.\\d{1,2})?") || !List.of("black", "brown", "dark-yellow", "dark-pink", "white").contains(colour)
                || !validStyle(style) || !validImage(image)) {
            sendJson(exchange, 400, "{\"message\":\"Check the product name, description, price, colour, style and image.\"}");
            return;
        }
        Product updated = new Product(id.isEmpty() ? UUID.randomUUID().toString() : id, name,
                description, price, colour, style, image);
        synchronized (DATA_LOCK) {
            int index = -1;
            for (int i = 0; i < PRODUCTS.size(); i++) {
                if (PRODUCTS.get(i).id.equals(id) && !id.isEmpty()) {
                    index = i;
                    break;
                }
            }
            if (!id.isEmpty() && index < 0) {
                sendJson(exchange, 404, "{\"message\":\"Product not found.\"}");
                return;
            }
            Product previous = index < 0 ? null : PRODUCTS.get(index);
            if (index < 0) PRODUCTS.add(updated);
            else PRODUCTS.set(index, updated);
            try {
                saveProducts();
            } catch (IOException exception) {
                if (previous == null) PRODUCTS.remove(updated);
                else PRODUCTS.set(index, previous);
                sendJson(exchange, 500, "{\"message\":\"Could not save the catalogue change. Please try again.\"}");
                return;
            }
        }
        sendJson(exchange, 200, "{\"message\":\"Product saved.\"}");
    }

    private static boolean validStyle(String style) {
        if (style.isEmpty() || style.length() > 50) return false;
        for (String token : style.split("\\s+")) {
            if (!List.of("kaftan", "print", "embroidered", "backless", "elegance",
                    "boubou", "wrap", "maxi", "flare", "occasion", "mixed").contains(token)) return false;
        }
        return true;
    }

    private static boolean validImage(String image) {
        if (!image.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,100}\\.(?i:jpg|jpeg|png|webp)")) return false;
        Path imageFile = SITE.resolve("images").resolve(image).normalize();
        return imageFile.getParent().equals(SITE.resolve("images"))
                && !Files.isSymbolicLink(imageFile) && Files.isRegularFile(imageFile);
    }

    private static void handleAdminContent(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"message\":\"Method not allowed\"}");
            return;
        }
        Map<String, String> fields = readForm(exchange);
        if (fields == null) return;
        Map<String, String> updated = new HashMap<>();
        for (String key : List.of("announcement", "heroDescription", "storyFirst", "storySecond")) {
            String value = fields.getOrDefault(key, "").trim();
            if (value.isEmpty() || value.length() > 500) {
                sendJson(exchange, 400, "{\"message\":\"All homepage text fields are required and must be under 500 characters.\"}");
                return;
            }
            updated.put(key, value);
        }
        synchronized (DATA_LOCK) {
            Map<String, String> previous = new HashMap<>(CONTENT);
            CONTENT.clear();
            CONTENT.putAll(updated);
            try {
                saveContent();
            } catch (IOException exception) {
                CONTENT.clear();
                CONTENT.putAll(previous);
                sendJson(exchange, 500, "{\"message\":\"Could not save homepage text. Please try again.\"}");
                return;
            }
        }
        sendJson(exchange, 200, "{\"message\":\"Homepage content saved.\"}");
    }

    private static void handleAdminRequests(HttpExchange exchange) throws IOException {
        List<CustomerRequest> snapshot;
        synchronized (DATA_LOCK) {
            snapshot = new ArrayList<>(CUSTOMER_REQUESTS);
        }
        List<String> json = new ArrayList<>();
        for (CustomerRequest request : snapshot) json.add(request.toJson());
        sendJson(exchange, 200, "[" + String.join(",", json) + "]");
    }

    private static void handleAdminImages(HttpExchange exchange) throws IOException {
        Path imageDirectory = SITE.resolve("images");
        if (!Files.isDirectory(imageDirectory)) {
            sendJson(exchange, 200, "[]");
            return;
        }
        List<String> images;
        try (var paths = Files.list(imageDirectory)) {
            images = paths.filter(path -> validImage(path.getFileName().toString()))
                    .map(path -> "\"" + jsonEscape(path.getFileName().toString()) + "\"")
                    .sorted()
                    .toList();
        }
        sendJson(exchange, 200, "[" + String.join(",", images) + "]");
    }

    private static void handleRequestStatus(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"message\":\"Method not allowed\"}");
            return;
        }
        Map<String, String> fields = readForm(exchange);
        if (fields == null) return;
        String id = fields.getOrDefault("id", "").trim();
        String status = fields.getOrDefault("status", "").trim();
        if (id.isEmpty()) {
            sendJson(exchange, 400, "{\"message\":\"A request id is required.\"}");
            return;
        }
        if (!List.of("new", "contacted", "complete").contains(status)) {
            sendJson(exchange, 400, "{\"message\":\"Choose a valid request status.\"}");
            return;
        }
        synchronized (DATA_LOCK) {
            CustomerRequest match = CUSTOMER_REQUESTS.stream().filter(request -> request.id.equals(id)).findFirst().orElse(null);
            if (match == null) {
                sendJson(exchange, 404, "{\"message\":\"Request not found.\"}");
                return;
            }
            String previous = match.status;
            match.status = status;
            try {
                saveRequests();
            } catch (IOException exception) {
                match.status = previous;
                sendJson(exchange, 500, "{\"message\":\"Could not save the request update. Please try again.\"}");
                return;
            }
        }
        sendJson(exchange, 200, "{\"message\":\"Request status updated.\"}");
    }

    private static void handleRequestDelete(HttpExchange exchange) throws IOException {
        if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
            sendJson(exchange, 405, "{\"message\":\"Method not allowed\"}");
            return;
        }
        Map<String, String> fields = readForm(exchange);
        if (fields == null) return;
        String id = fields.getOrDefault("id", "").trim();
        if (id.isEmpty()) {
            sendJson(exchange, 400, "{\"message\":\"A request id is required.\"}");
            return;
        }
        synchronized (DATA_LOCK) {
            int index = -1;
            for (int i = 0; i < CUSTOMER_REQUESTS.size(); i++) {
                if (CUSTOMER_REQUESTS.get(i).id.equals(id)) {
                    index = i;
                    break;
                }
            }
            if (index < 0) {
                sendJson(exchange, 404, "{\"message\":\"Request not found.\"}");
                return;
            }
            CustomerRequest removed = CUSTOMER_REQUESTS.remove(index);
            try {
                saveRequests();
            } catch (IOException exception) {
                CUSTOMER_REQUESTS.add(index, removed);
                sendJson(exchange, 500, "{\"message\":\"Could not save this request change. Please try again.\"}");
                return;
            }
        }
        sendJson(exchange, 200, "{\"message\":\"Request deleted.\"}");
    }

    private static Map<String, String> readForm(HttpExchange exchange) throws IOException {
        String type = exchange.getRequestHeaders().getFirst("Content-Type");
        if (type == null || !type.toLowerCase().startsWith("application/x-www-form-urlencoded")) {
            sendJson(exchange, 415, "{\"message\":\"Expected form-encoded data.\"}");
            return null;
        }
        byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST_BYTES + 1);
        if (body.length > MAX_REQUEST_BYTES) {
            sendJson(exchange, 413, "{\"message\":\"Request is too large.\"}");
            return null;
        }
        try {
            return parseForm(new String(body, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException exception) {
            sendJson(exchange, 400, "{\"message\":\"Form data is malformed.\"}");
            return null;
        }
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> fields = new HashMap<>();
        for (String pair : body.split("&")) {
            if (pair.isEmpty()) continue;
            int separator = pair.indexOf('=');
            String key = separator < 0 ? pair : pair.substring(0, separator);
            String value = separator < 0 ? "" : pair.substring(separator + 1);
            fields.put(decodeFormValue(key), decodeFormValue(value));
        }
        return fields;
    }

    private static String decodeFormValue(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Form data contains malformed URL encoding.", exception);
        }
    }

    private static String queryParameter(HttpExchange exchange, String name) {
        String query = exchange.getRequestURI().getRawQuery();
        if (query == null) return "";
        for (String pair : query.split("&")) {
            if (pair.isEmpty()) continue;
            int separator = pair.indexOf('=');
            String key = separator < 0 ? pair : pair.substring(0, separator);
            try {
                String decodedKey = URLDecoder.decode(key, StandardCharsets.UTF_8);
                if (name.equals(decodedKey)) {
                    String decodedValue = separator < 0 ? "" : URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8);
                    return decodedValue;
                }
            } catch (IllegalArgumentException ignored) {
                return "";
            }
        }
        return "";
    }

    private static void saveProducts() throws IOException {
        List<String> lines = new ArrayList<>();
        for (Product product : PRODUCTS) {
            lines.add(encodeFields(product.id, product.name, product.description, product.price,
                    product.colour, product.style, product.image));
        }
        saveFile(DATA.resolve("products.tsv"), lines);
    }

    private static void saveContent() throws IOException {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, String> entry : CONTENT.entrySet()) {
            lines.add(encodeFields(entry.getKey(), entry.getValue()));
        }
        saveFile(DATA.resolve("content.tsv"), lines);
    }

    private static void saveRequests() throws IOException {
        List<String> lines = new ArrayList<>();
        for (CustomerRequest request : CUSTOMER_REQUESTS) {
            lines.add(encodeFields(request.id, request.customer, request.email, request.items,
                    request.finish, request.createdAt, request.status));
        }
        saveFile(DATA.resolve("requests.tsv"), lines);
    }

    private static String encodeFields(String... fields) {
        List<String> encoded = new ArrayList<>();
        for (String field : fields) {
            encoded.add(Base64.getUrlEncoder().withoutPadding().encodeToString(field.getBytes(StandardCharsets.UTF_8)));
        }
        return String.join("\t", encoded);
    }

    private static List<String> decodeFields(String line) {
        List<String> fields = new ArrayList<>();
        try {
            for (String value : line.split("\t", -1)) {
                fields.add(new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8));
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("A PeaceCollection data file contains malformed data.", exception);
        }
        return fields;
    }

    private static void saveFile(Path target, List<String> lines) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(temporary, lines, StandardCharsets.UTF_8);
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String mapToJson(Map<String, String> values) {
        List<String> fields = new ArrayList<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            fields.add("\"" + jsonEscape(entry.getKey()) + "\":\"" + jsonEscape(entry.getValue()) + "\"");
        }
        return "{" + String.join(",", fields) + "}";
    }

    private static String jsonEscape(String value) {
        StringBuilder escaped = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) escaped.append(String.format("\\u%04x", (int) character));
                    else escaped.append(character);
                }
            }
        }
        return escaped.toString();
    }

    private static String randomToken(int bytes) {
        byte[] token = new byte[bytes];
        RANDOM.nextBytes(token);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(token);
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (actual == null) return false;
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), actual.getBytes(StandardCharsets.UTF_8));
    }

    private static void sendJson(HttpExchange exchange, int status, String body) throws IOException {
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        send(exchange, status, "application/json; charset=UTF-8", body);
    }

    private static void send(HttpExchange exchange, int status, String type, String body) throws IOException {
        send(exchange, status, type, body.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, int status, String type, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
        exchange.sendResponseHeaders(status, body.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private static final class Product {
        private final String id;
        private final String name;
        private final String description;
        private final String price;
        private final String colour;
        private final String style;
        private final String image;

        private Product(String id, String name, String description, String price,
                        String colour, String style, String image) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.price = price;
            this.colour = colour;
            this.style = style;
            this.image = image;
        }

        private String toJson() {
            return "{\"id\":\"" + jsonEscape(id) + "\",\"name\":\"" + jsonEscape(name)
                    + "\",\"description\":\"" + jsonEscape(description) + "\",\"price\":\"" + jsonEscape(price)
                    + "\",\"colour\":\"" + jsonEscape(colour) + "\",\"style\":\"" + jsonEscape(style)
                    + "\",\"image\":\"" + jsonEscape(image) + "\"}";
        }
    }

    private static final class CustomerRequest {
        private final String id;
        private final String customer;
        private final String email;
        private final String items;
        private final String finish;
        private final String createdAt;
        private String status;

        private CustomerRequest(String id, String customer, String email, String items,
                                String finish, String createdAt, String status) {
            this.id = id;
            this.customer = customer;
            this.email = email;
            this.items = items;
            this.finish = finish;
            this.createdAt = createdAt;
            this.status = status;
        }

        private String toJson() {
            return "{\"id\":\"" + jsonEscape(id) + "\",\"customer\":\"" + jsonEscape(customer)
                    + "\",\"email\":\"" + jsonEscape(email) + "\",\"items\":\"" + jsonEscape(items)
                    + "\",\"finish\":\"" + jsonEscape(finish) + "\",\"createdAt\":\"" + jsonEscape(createdAt)
                    + "\",\"status\":\"" + jsonEscape(status) + "\"}";
        }
    }

    private static final class Session {
        private final String id;
        private final String csrf;
        private final long expiresAt;

        private Session(String id, String csrf, long expiresAt) {
            this.id = id;
            this.csrf = csrf;
            this.expiresAt = expiresAt;
        }
    }

    private static final class LoginAttempts {
        private int failures;
        private long lockedUntil;
    }
}
