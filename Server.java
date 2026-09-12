import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpExchange;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.Properties;
import javax.mail.*;
import javax.mail.internet.*;
import java.nio.charset.StandardCharsets;

/**
 * سيرفر PlayHive - نسخة HTTP (تعمل على أي استضافة سحابية عادية مثل Render).
 * يستقبل طلبات POST على المسار /api، النص بنفس صيغة الأوامر القديمة بالضبط
 * (مثلاً: LOGIN|username|email|password)، ويرد بنفس الصيغة (OK|رسالة أو ERROR|رسالة).
 */
public class Server {

    private static final String ACCOUNTS_FILE = "accounts.txt";
    private static final ConcurrentHashMap<String, String[]> accounts = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, String> pendingCodes = new ConcurrentHashMap<>();

    private static final String GMAIL_ADDRESS = "Mouadbougadi@gmail.com";
    private static final String GMAIL_APP_PASSWORD = "PUT_YOUR_APP_PASSWORD_HERE";

    private static class Lobby {
        String id;
        String gameType;
        String hostUsername;
        boolean isPublic;
        String password;
        List<String> members = Collections.synchronizedList(new ArrayList<>());
        int maxPlayers = 0;
    }

    private static final ConcurrentHashMap<String, Lobby> lobbies = new ConcurrentHashMap<>();
    private static final AtomicIntegerLike lobbyCounter = new AtomicIntegerLike();

    private static class AtomicIntegerLike {
        private int value = 1000;
        synchronized int next() { return value++; }
    }

    public static void main(String[] args) throws IOException {
        loadAccounts();

        // Render (وأغلب الاستضافات) تعطي رقم المنفذ عبر متغير بيئة اسمه PORT
        String portEnv = System.getenv("PORT");
        int port = portEnv != null ? Integer.parseInt(portEnv) : 5050;

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/api", Server::handleApi);
        server.createContext("/", exchange -> {
            String response = "PlayHive server is running.";
            exchange.sendResponseHeaders(200, response.getBytes(StandardCharsets.UTF_8).length);
            OutputStream os = exchange.getResponseBody();
            os.write(response.getBytes(StandardCharsets.UTF_8));
            os.close();
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.println("السيرفر شغال على المنفذ " + port + " ... بانتظار الطلبات.");
    }

    private static void handleApi(HttpExchange exchange) throws IOException {
        String requestBody;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(exchange.getRequestBody(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            requestBody = sb.toString();
        }

        System.out.println("استلمنا: " + requestBody);
        String response = processRequest(requestBody);

        byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=UTF-8");
        exchange.sendResponseHeaders(200, responseBytes.length);
        OutputStream os = exchange.getResponseBody();
        os.write(responseBytes);
        os.close();
    }

    private static synchronized String processRequest(String line) {
        String[] parts = line.split("\\|", -1);
        if (parts.length == 0) return "ERROR|طلب غير مفهوم";

        String command = parts[0];

        if (command.equals("SEND_CODE") && parts.length == 2) {
            String email = parts[1];
            String code = String.valueOf(1000 + new Random().nextInt(9000));
            pendingCodes.put(email, code);
            boolean sent = sendVerificationEmail(email, code);
            return sent ? "OK|تم إرسال الرمز إلى بريدك الإلكتروني"
                        : "ERROR|تعذر إرسال البريد، تحقق من إعدادات السيرفر";
        }

        if (command.equals("CREATE_ACCOUNT") && parts.length == 5) {
            String username = parts[1];
            String email = parts[2];
            String password = parts[3];
            String code = parts[4];

            String expectedCode = pendingCodes.get(email);
            if (expectedCode == null || !expectedCode.equals(code)) {
                return "ERROR|رمز التحقق غير صحيح";
            }
            if (accounts.containsKey(username)) {
                return "ERROR|اسم المستخدم موجود مسبقاً";
            }
            accounts.put(username, new String[]{email, password});
            pendingCodes.remove(email);
            saveAccounts();
            return "OK|تم إنشاء الحساب بنجاح";
        }

        if (command.equals("LOGIN") && parts.length == 4) {
            String username = parts[1];
            String email = parts[2];
            String password = parts[3];
            String[] stored = accounts.get(username);
            if (stored == null) return "ERROR|الحساب غير موجود";
            if (!stored[0].equals(email) || !stored[1].equals(password)) {
                return "ERROR|البريد الإلكتروني أو كلمة المرور غير صحيحة";
            }
            return "OK|تم تسجيل الدخول بنجاح";
        }

        if (command.equals("CREATE_LOBBY") && parts.length == 5) {
            String gameType = parts[1];
            boolean isPublic = parts[2].equals("true");
            String password = parts[3];
            String username = parts[4];

            Lobby lobby = new Lobby();
            lobby.id = "L" + lobbyCounter.next();
            lobby.gameType = gameType;
            lobby.isPublic = isPublic;
            lobby.password = password;
            lobby.hostUsername = username;
            lobby.members.add(username);
            lobbies.put(lobby.id, lobby);

            return "OK|" + lobby.id;
        }

        if (command.equals("LIST_LOBBIES") && parts.length == 2) {
            String gameType = parts[1];
            StringBuilder sb = new StringBuilder();
            for (Lobby lobby : lobbies.values()) {
                if (lobby.gameType.equals(gameType) && lobby.isPublic) {
                    if (sb.length() > 0) sb.append(";");
                    sb.append(lobby.id).append(",").append(lobby.hostUsername)
                      .append(",").append(lobby.members.size());
                }
            }
            return "OK|" + sb;
        }

        if (command.equals("JOIN_LOBBY") && parts.length == 4) {
            String lobbyId = parts[1];
            String password = parts[2];
            String username = parts[3];

            Lobby lobby = lobbies.get(lobbyId);
            if (lobby == null) return "ERROR|الصالة غير موجودة أو انتهت";
            if (!lobby.isPublic && !lobby.password.equals(password)) {
                return "ERROR|كلمة المرور غير صحيحة";
            }
            if (lobby.maxPlayers > 0 && lobby.members.size() >= lobby.maxPlayers) {
                return "ERROR|الصالة ممتلئة";
            }
            if (!lobby.members.contains(username)) {
                lobby.members.add(username);
            }
            return "OK|" + lobbyId;
        }

        if (command.equals("LOBBY_INFO") && parts.length == 2) {
            Lobby lobby = lobbies.get(parts[1]);
            if (lobby == null) return "ERROR|الصالة غير موجودة";
            return "OK|" + lobby.hostUsername + "," + lobby.members.size() + ","
                    + String.join(",", lobby.members);
        }

        if (command.equals("LEAVE_LOBBY") && parts.length == 3) {
            Lobby lobby = lobbies.get(parts[1]);
            if (lobby != null) {
                lobby.members.remove(parts[2]);
                if (lobby.members.isEmpty()) {
                    lobbies.remove(parts[1]);
                }
            }
            return "OK|تمت المغادرة";
        }

        return "ERROR|طلب غير مفهوم أو ناقص البيانات";
    }

    private static boolean sendVerificationEmail(String toEmail, String code) {
        Properties props = new Properties();
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.host", "smtp.gmail.com");
        props.put("mail.smtp.port", "587");

        Session session = Session.getInstance(props, new javax.mail.Authenticator() {
            protected javax.mail.PasswordAuthentication getPasswordAuthentication() {
                return new javax.mail.PasswordAuthentication(GMAIL_ADDRESS, GMAIL_APP_PASSWORD);
            }
        });

        try {
            Message message = new MimeMessage(session);
            message.setFrom(new InternetAddress(GMAIL_ADDRESS));
            message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(toEmail));
            message.setSubject("رمز التحقق - PlayHive");
            message.setText("رمز التحقق الخاص بك هو: " + code);
            Transport.send(message);
            System.out.println("تم إرسال البريد إلى: " + toEmail);
            return true;
        } catch (MessagingException e) {
            System.out.println("خطأ بإرسال البريد: " + e.getMessage());
            return false;
        }
    }

    private static void loadAccounts() {
        File file = new File(ACCOUNTS_FILE);
        if (!file.exists()) return;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split("\\|");
                if (parts.length == 3) {
                    accounts.put(parts[0], new String[]{parts[1], parts[2]});
                }
            }
        } catch (IOException e) {
            System.out.println("خطأ بقراءة ملف الحسابات: " + e.getMessage());
        }
    }

    private static void saveAccounts() {
        try (PrintWriter writer = new PrintWriter(new FileWriter(ACCOUNTS_FILE))) {
            for (Map.Entry<String, String[]> entry : accounts.entrySet()) {
                writer.println(entry.getKey() + "|" + entry.getValue()[0] + "|" + entry.getValue()[1]);
            }
        } catch (IOException e) {
            System.out.println("خطأ بحفظ ملف الحسابات: " + e.getMessage());
        }
    }
}
