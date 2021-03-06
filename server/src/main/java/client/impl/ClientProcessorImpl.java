package client.impl;

import client.ClientProcessor;
import com.sun.istack.internal.NotNull;
import model.*;
import model.FileDescriptor;
import model.enums.ResponseType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import server.ServerServices;

import java.io.*;
import java.net.Socket;
import java.net.SocketException;
import java.util.*;

public class ClientProcessorImpl implements ClientProcessor {

    private static final Logger logger = LogManager.getLogger(ClientProcessorImpl.class);

    private Socket socket;
    private ServerServices server;
    private ObjectOutputStream writer;
    private ObjectInputStream reader;

    private final Collection<User> users = new LinkedList<>();
    //Files
    private final Map<Long, List<FileContent>> fileContents = new HashMap<>();
    private final Map<Long, FileDescriptor> fileDescriptors = new HashMap<>();
    //Audios
    private final Map<Long, List<AudioContent>> audioContents = new HashMap<>();
    private final Map<Long, AudioDescriptor> audioDescriptors = new HashMap<>();

    private User user;

    private boolean isRunning = true;

    public ClientProcessorImpl() {

    }

    public ClientProcessorImpl(final Socket socket, final ServerServices server) {
        this.socket = socket;
        this.server = server;
    }

    private Response buildResponse(final ResponseType type, final Content content) {
        return Response.newInstance(type, content, user);
    }

    private Response buildResponse(final ResponseType type, final Content content, final User newOrigin) {
        return Response.newInstance(type, content, newOrigin);
    }

    public synchronized boolean sendResponse(@NotNull final Response response) throws IOException {
        if (writer != null) {
            writer.writeObject(response);
            writer.flush();
            return true;
        }
        return false;
    }

    private Request getRequest() throws IOException, ClassNotFoundException {
        Object obj = reader.readObject();
        logger.info(obj);
        if (obj instanceof Request) return (Request)obj;
        return null;
    }

    @Override
    public void run() {
        try {
            writer = new ObjectOutputStream(new BufferedOutputStream(socket.getOutputStream()));
            writer.writeBoolean(true);
            writer.flush();
            reader = new ObjectInputStream(new BufferedInputStream(socket.getInputStream()));
            while(!socket.isClosed() && isRunning) {
                final Request request = getRequest();

                if (!isRunning) {
                    logger.error("Connexion ended by Server ...");
                    break;
                }
                if (request == null) {
                    logger.error("Request is empty.");
                    handleError(ResponseType.WRONG_PARAMETERS);
                } else switch (request.getType()) {
                    case CONNECT: {
                        acceptConnection(request);
                        break;
                    }
                    case SEND_MESSAGE: {
                        sendMessage(request);
                        break;
                    }
                    case PREPARE_SEND_FILE: {
                        checkFile(request);
                        break;
                    }
                    case SEND_FILE: {
                        handleFile(request);
                        break;
                    }
                    case PREPARE_REQUEST_FILE: {
                        prepareSendFile(request);
                        break;
                    }
                    case REQUEST_FILE: {
                        sendFile(request);
                        break;
                    }
                    case PREPARE_SEND_AUDIO: {
                        checkAudio(request);
                        break;
                    }
                    case SEND_AUDIO: {
                        handleAudio(request);
                        break;
                    }
                    case PREPARE_REQUEST_AUDIO: {
                        prepareSendAudio(request);
                        break;
                    }
                    case REQUEST_AUDIO: {
                        sendAudio(request);
                        break;
                    }
                    case REQUEST_CONTROL: {
                        requestControl(request);
                        break;
                    }
                    case SEND_FRAME: {
                        sendFrame(request);
                        break;
                    }
                    case PROVOKE_EVENT: {
                        provokeEvent(request);
                        break;
                    }
                    case STOP_CONTROL: {
                        stopControl(request);
                        break;
                    }
                    case DISCONNECT: {
                        removeConnection();
                        return;
                    }
                    default: {
                        logger.error("Unexpected value : " + request.getType());
                    }
                }
            }
        } catch(SocketException e) {
            logger.info("Connexion Interrupted with : " + user);
        } catch (IOException | ClassNotFoundException e) {
            e.printStackTrace();
        }

        logger.info("Connexion Lost with : " + user);
        //Treat it as a disconnection.
        this.removeConnection();
    }

    @Override
    public void acceptConnection(Request request) throws IOException {
        logger.info("Connexion Accepted.");

        //Creating a User corresponding to the current connexion and adding it to the global list of Users.
        user = User.newInstance(socket.getInetAddress().getHostAddress(), ((Credentials)request.getContent()).getUsername());
        server.addUser(user, this, users);

        System.out.println(buildResponse(ResponseType.CONNECTED, ContextContent.newInstance((List<User>)users)));
        this.sendResponse(buildResponse(ResponseType.CONNECTED, ContextContent.newInstance((List<User>)users)));

        //Sending a Response to all the known users so that they know about the current user
        final Response response = buildResponse(ResponseType.ADD_USER, null);

        ClientProcessor client;
        long id = user.getId();
        synchronized (this.users) {
            for (User u : users) {
                logger.info("Sending Response (ADD) to User : " + u.getId() + " From : " + id);
                client = server.findClient(u.getId());
                if (client != null) {
                    client.addUser(user, response);
                } else {
                    logger.warn("Couldn't find Client for User : " + u.getId() + " From : " + id);
                }
            }
        }
    }

    @Override
    public void removeConnection() {
        logger.info("Disconnection from User : " + user.getId());
        this.close();
        this.removeUser();
    }

    private void removeUser() {
        //Removing from the server's list of Users and Clients.
        server.removeUser(user.getId());

        //Sending a Response to all the known users so that they remove the current user
        final Response response = buildResponse(ResponseType.REMOVE_USER, null);

        ClientProcessor client;
        final long id = user.getId();
        synchronized (this.users) {
            for (User u : users) {
                logger.info("Sending Response (REMOVE) to User : " + u.getId() + " From : " + id);
                client = server.findClient(u.getId());
                if (client != null) {
                    try {
                        client.removeUser(user, response);
                    } catch (IOException e) {
                        logger.error("Exception while removing user ...");
                        e.printStackTrace();
                    }
                } else {
                    logger.warn("Couldn't find Client for User : " + u.getId() + " From : " + id);
                }
            }
        }
    }

    @Override
    public void sendMessage(Request request) throws IOException {
        if (request.getDestination() == null) {
            handleError(ResponseType.WRONG_PARAMETERS);
            return;
        }

        this.sendMessage(request.getContent(), request.getDestination(), ResponseType.MESSAGE, ResponseType.MESSAGE_SENT);
    }

    private void sendMessage(Content content, User destination, ResponseType type, ResponseType successResponseType) throws IOException {
        final ClientProcessor client = server.findClient(destination.getId());

        //Sending the Message to the destination.
        final Response message = buildResponse(type, content);

        if (client != null && client.sendResponse(message)) {
            //Sending Success Response to the User.
            sendResponse(buildResponse(successResponseType, content));
        } else {
            //Sending Failure Response to the User.
            handleError(ResponseType.DESTINATION_NOT_FOUND);
        }
    }

    @Override
    public void checkFile(Request request) throws IOException {
        final FileDescriptor descriptor = (FileDescriptor)request.getContent();
        if (descriptor.getChunksTotalNumber() < ClientProcessor.FILE_SIZE_THRESHOLD) {
            //File size inferior to the maximum authorized, tell the client to start sending the file.
            synchronized (this.fileDescriptors) {
                this.fileDescriptors.put(descriptor.getFileId(), descriptor);
            }
            sendResponse(buildResponse(
                    ResponseType.CAN_SEND_FILE,
                    FileBasicInformation.newInstance(descriptor.getFileId()),
                    request.getDestination()));
        } else {
            //File is too big, tell the client to not send the file.
            sendResponse(buildResponse(ResponseType.INSUFFICIENT_MEMORY, null));
        }
    }

    @Override
    public void handleFile(Request request) throws IOException {
        final FileContent fileContent = (FileContent)request.getContent();
        boolean error = fileContent == null;
        synchronized (fileDescriptors) {
            error = error || !fileDescriptors.containsKey(fileContent.getFileId());
        }
        if (error) {
            handleError(ResponseType.WRONG_PARAMETERS);
            return;
        }

        List<FileContent> list;
        synchronized (fileContents) {
            if (!fileContents.containsKey(fileContent.getFileId())) {
                fileContents.put(fileContent.getFileId(), new LinkedList<>());
            }
            list = fileContents.get(fileContent.getFileId());
        }

        list.add(fileContent);

        final FileDescriptor fileDescriptor = this.getFileDescriptor(fileContent.getFileId());
        if (list.size() == fileDescriptor.getChunksTotalNumber()) {
            //If all the parts are received, send a FileMessage to the destination, and tell
            // the sending user that the file has been sent.
            sendMessage(
                    FileMessageContent.newInstance(fileDescriptor),
                    request.getDestination(),
                    ResponseType.FILE_MESSAGE,
                    ResponseType.FILE_SENT);
        }
    }

    @Override
    public void prepareSendFile(Request request) throws IOException {
        final ClientProcessor client = (request.getDestination() == null)
                ? this
                : server.findClient(request.getDestination().getId());

        final FileDescriptor fileDescriptor = client.getFileDescriptor(((FileBasicInformation)request.getContent()).getFileId());
        if (fileDescriptor == null) {
            handleError(ResponseType.WRONG_PARAMETERS);
            return;
        }

        sendResponse(buildResponse(
                ResponseType.PREPARE_RECEIVE_FILE,
                fileDescriptor,
                (request.getDestination() == null) ? user : request.getDestination()));
    }

    @Override
    public void sendFile(Request request) throws IOException {
        final FileBasicInformation fileBasicInformation = (FileBasicInformation)request.getContent();
        final ClientProcessor client = server.findClient(request.getDestination().getId());

        final FileDescriptor fileDescriptor = client.getFileDescriptor(fileBasicInformation.getFileId());

        if (fileDescriptor == null || request.getDestination() == null) {
            handleError(ResponseType.WRONG_PARAMETERS);
            return;
        }

        final List<FileContent> list = client.getFileContents(fileBasicInformation.getFileId());
        if (list.size() != fileDescriptor.getChunksTotalNumber()) {
            handleError(ResponseType.WRONG_PARAMETERS);
            return;
        }

        for (FileContent fc : list) {
            sendResponse(buildResponse(ResponseType.FILE_CHUNK, fc, request.getDestination()));
        }
    }

    @Override
    public void checkAudio(Request request) throws IOException {
        final AudioDescriptor descriptor = (AudioDescriptor) request.getContent();
        if (descriptor.getChunksTotalNumber() < ClientProcessor.AUDIO_SIZE_THRESHOLD) {
            //Audio file size inferior to the maximum authorized, tell the client to start sending the file.
            synchronized ( this.audioDescriptors) {
                this.audioDescriptors.put(descriptor.getAudioId(), descriptor);
            }
            sendResponse(buildResponse(
                    ResponseType.CAN_SEND_AUDIO,
                    AudioBasicInformation.newInstance(descriptor.getAudioId()),
                    request.getDestination()));
        } else {
            //File is too big, tell the client to not send the file.
            sendResponse(buildResponse(ResponseType.INSUFFICIENT_MEMORY, null));
        }
    }

    @Override
    public void handleAudio(Request request) throws IOException {
        final AudioContent audioContent = (AudioContent) request.getContent();

        boolean error = audioContent == null;
        synchronized (audioDescriptors) {
            error = error || !audioDescriptors.containsKey(audioContent.getAudioId());
        }
        if (error) {
            handleError(ResponseType.WRONG_PARAMETERS);
            return;
        }


        List<AudioContent> list;
        synchronized (audioContents) {
            if (!audioContents.containsKey(audioContent.getAudioId())) {
                audioContents.put(audioContent.getAudioId(), new LinkedList<>());
            }
            list = audioContents.get(audioContent.getAudioId());
        }


        list.add(audioContent);

        final AudioDescriptor audioDescriptor = this.getAudioDescriptor(audioContent.getAudioId());
        if (list.size() == audioDescriptor.getChunksTotalNumber()) {
            //If all the parts are received, send an Audio Message to the destination, and tell
            // the sending user that the file has been sent.
            sendMessage(
                    AudioMessageContent.newInstance(audioDescriptor),
                    request.getDestination(),
                    ResponseType.AUDIO_MESSAGE,
                    ResponseType.AUDIO_SENT);
        }
    }

    @Override
    public void prepareSendAudio(Request request) throws IOException {
        final ClientProcessor client = (request.getDestination() == null)
                ? this
                : server.findClient(request.getDestination().getId());

        final AudioDescriptor audioDescriptor = client.getAudioDescriptor(
                ((AudioBasicInformation)request.getContent()).getAudioId());
        if (audioDescriptor == null) {
            handleError(ResponseType.WRONG_PARAMETERS);
            return;
        }

        sendResponse(buildResponse(
                ResponseType.PREPARE_RECEIVE_AUDIO,
                audioDescriptor,
                (request.getDestination() == null) ? user : request.getDestination()));
    }

    @Override
    public void sendAudio(Request request) throws IOException {
        final AudioBasicInformation audioBasicInformation = (AudioBasicInformation) request.getContent();
        final ClientProcessor client = server.findClient(request.getDestination().getId());

        final AudioDescriptor audioDescriptor = client.getAudioDescriptor(audioBasicInformation.getAudioId());

        if (audioDescriptor == null || request.getDestination() == null) {
            handleError(ResponseType.WRONG_PARAMETERS);
            return;
        }

        final List<AudioContent> list = client.getAudioContents(audioBasicInformation.getAudioId());
        if (list.size() != audioDescriptor.getChunksTotalNumber()) {
            handleError(ResponseType.WRONG_PARAMETERS);
            return;
        }

        for (AudioContent fc : list) {
            sendResponse(buildResponse(ResponseType.AUDIO_CHUNK, fc, request.getDestination()));
        }
    }

    @Override
    public void requestControl(Request request) throws IOException {
        if (request.getDestination() == null) {
            handleError(ResponseType.WRONG_PARAMETERS);
            return;
        }

        final ClientProcessor client = server.findClient(request.getDestination().getId());
        if (client == null) {
            handleError(ResponseType.DESTINATION_NOT_FOUND);
            return;
        }

        client.sendResponse(buildResponse(ResponseType.CONTROL_REQUEST, request.getContent()));
    }

    @Override
    public void stopControl(Request request) throws IOException {
        if (request.getDestination() == null) {
            handleError(ResponseType.WRONG_PARAMETERS);
            return;
        }

        final ClientProcessor client = server.findClient(request.getDestination().getId());
        if (client == null) {
            handleError(ResponseType.DESTINATION_NOT_FOUND);
            return;
        }

        client.sendResponse(buildResponse(ResponseType.END_CONTROL, request.getContent()));
    }

    @Override
    public void sendFrame(Request request) throws IOException {
        if (request.getDestination() == null) {
            handleError(ResponseType.WRONG_PARAMETERS);
            return;
        }

        final ClientProcessor client = server.findClient(request.getDestination().getId());
        if (client == null) {
            sendResponse(buildResponse(ResponseType.END_CONTROL, null));
            return;
        }

        client.sendResponse(buildResponse(ResponseType.FRAME, request.getContent()));
    }

    @Override
    public void provokeEvent(Request request) throws IOException {
        if (request.getDestination() == null) {
            handleError(ResponseType.WRONG_PARAMETERS);
            return;
        }

        final ClientProcessor client = server.findClient(request.getDestination().getId());
        if (client == null) {
            sendResponse(buildResponse(ResponseType.DESTINATION_NOT_FOUND, null));
            return;
        }

        client.sendResponse(buildResponse(ResponseType.PROVOKE_EVENT, request.getContent()));
    }

    @Override
    public void addUser(User from, Response response) throws IOException {
        synchronized (this.users) {
            this.users.add(from);
        }
        this.sendResponse(response);
    }

    @Override
    public void removeUser(User from, Response response) throws IOException {
        synchronized (this.users) {
            this.users.remove(from);
        }
        this.sendResponse(response);
    }

    @Override
    public void handleError(ResponseType type) throws IOException {
        if (!socket.isClosed()) {
            this.sendResponse(this.buildResponse(type, null));
        }
    }

    @Override
    public void close() {
        try {
            synchronized (this) {
                writer = null;
                reader = null;
                this.isRunning = false;
                this.socket.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    @Override
    public List<FileContent> getFileContents(long fileId) {
        synchronized (fileContents) {
            return fileContents.get(fileId);
        }
    }

    @Override
    public FileDescriptor getFileDescriptor(long fileId) {
        synchronized (fileDescriptors) {
            return fileDescriptors.get(fileId);
        }
    }

    @Override
    public List<AudioContent> getAudioContents(long audioId) {
        synchronized (audioContents) {
            return audioContents.get(audioId);
        }
    }

    @Override
    public AudioDescriptor getAudioDescriptor(long audioId) {
        synchronized (audioDescriptors) {
            return audioDescriptors.get(audioId);
        }
    }
}
