package server.websocket;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.*;

public class TextEditorWebSocketHandler extends TextWebSocketHandler {

    private final String baseDir = "shared_files";
    private final Set<WebSocketSession> sessions = Collections.synchronizedSet(new HashSet<>());

    private final Map<String, Map<Integer, String>> fileLocks = new HashMap<>();
    private final Map<WebSocketSession, LockInfo> sessionLocks = new HashMap<>();
    private final Map<WebSocketSession, String> clientIdMap = new HashMap<>();

    private static class LockInfo {
        String filename;
        int line;
        LockInfo(String filename, int line) {
            this.filename = filename;
            this.line = line;
        }
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.add(session);

        String query = session.getUri().getQuery();
        if (query != null) {
            for (String param : query.split("&")) {
                if (param.startsWith("clientId=") || param.startsWith("nickname=")) {
                    clientIdMap.put(session, param.split("=")[1]);
                    break;
                }
            }
        }
        sendFileListTo(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        clientIdMap.remove(session);

        if (sessionLocks.containsKey(session)) {
            LockInfo info = sessionLocks.remove(session);
            Map<Integer, String> locks = fileLocks.get(info.filename);
            if (locks != null) {
                locks.remove(info.line);
                broadcastLockState(info.filename);
            }
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        JsonObject json = JsonParser.parseString(message.getPayload()).getAsJsonObject();
        String type = json.get("type").getAsString();

        switch (type) {
            case "create_file" -> {
                String filename = json.get("filename").getAsString();
                File file = new File(baseDir, filename);
                if (!file.exists()) {
                    file.createNewFile();
                }
                sendFileListToAll();
            }
            case "read_file" -> {
                String filename = json.get("filename").getAsString();
                File file = new File(baseDir, filename);
                String content = file.exists() ? Files.readString(file.toPath()) : "";

                JsonObject response = new JsonObject();
                response.addProperty("type", "file_content");
                response.addProperty("filename", filename);
                response.addProperty("content", content);
                response.addProperty("senderId", clientIdMap.get(session));
                session.sendMessage(new TextMessage(response.toString()));

                broadcastLockState(filename);
            }
            case "update_file" -> {
                String filename = json.get("filename").getAsString();
                String content = json.get("content").getAsString();

                File file = new File(baseDir, filename);
                Files.writeString(file.toPath(), content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

                JsonObject updateMsg = new JsonObject();
                updateMsg.addProperty("type", "file_update_broadcast");
                updateMsg.addProperty("filename", filename);
                updateMsg.addProperty("content", content);
                updateMsg.addProperty("senderId", clientIdMap.get(session));

                broadcast(updateMsg.toString());
            }
            case "delete_file" -> {
                String filename = json.get("filename").getAsString();
                File file = new File(baseDir, filename);
                if (file.exists()) {
                    file.delete();
                }
                sendFileListToAll();
            }
            case "lock_request" -> {
                String filename = json.get("filename").getAsString();
                int line = json.get("line").getAsInt();
                String userId = clientIdMap.get(session);

                fileLocks.putIfAbsent(filename, new HashMap<>());
                Map<Integer, String> locks = fileLocks.get(filename);

                if (sessionLocks.containsKey(session)) {
                    LockInfo prev = sessionLocks.remove(session);
                    Map<Integer, String> prevLocks = fileLocks.get(prev.filename);
                    if (prevLocks != null) prevLocks.remove(prev.line);
                }

                boolean granted = false;
                if (!locks.containsKey(line) || locks.get(line).equals(userId)) {
                    locks.put(line, userId);
                    sessionLocks.put(session, new LockInfo(filename, line));
                    granted = true;
                }

                broadcastLockState(filename);
            }
            case "unlock_request" -> {
                String filename = json.get("filename").getAsString();
                int line = json.get("line").getAsInt();
                String userId = clientIdMap.get(session);
                Map<Integer, String> locks = fileLocks.get(filename);
                if (locks != null && locks.getOrDefault(line, "").equals(userId)) {
                    locks.remove(line);
                    broadcastLockState(filename);
                }
            }
        }
    }

    private void sendFileListTo(WebSocketSession session) throws Exception {
        File folder = new File(baseDir);
        if (!folder.exists()) folder.mkdirs();

        File[] files = folder.listFiles();
        JsonArray fileArray = new JsonArray();
        if (files != null) {
            for (File file : files) {
                fileArray.add(file.getName());
            }
        }

        JsonObject response = new JsonObject();
        response.addProperty("type", "file_list");
        response.add("files", fileArray);

        session.sendMessage(new TextMessage(response.toString()));
    }

    private void sendFileListToAll() throws Exception {
        for (WebSocketSession s : sessions) {
            sendFileListTo(s);
        }
    }

    private void broadcast(String message) throws Exception {
        for (WebSocketSession s : sessions) {
            s.sendMessage(new TextMessage(message));
        }
    }

    private void broadcastLockState(String filename) {
        try {
            JsonObject msg = new JsonObject();
            msg.addProperty("type", "lock_state");
            msg.addProperty("filename", filename);

            JsonArray lockedLines = new JsonArray();
            Map<Integer, String> locks = fileLocks.getOrDefault(filename, new HashMap<>());

            for (Map.Entry<Integer, String> entry : locks.entrySet()) {
                JsonObject obj = new JsonObject();
                obj.addProperty("line", entry.getKey());
                obj.addProperty("lockedBy", entry.getValue());
                lockedLines.add(obj);
            }

            msg.add("locks", lockedLines);
            broadcast(msg.toString());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
