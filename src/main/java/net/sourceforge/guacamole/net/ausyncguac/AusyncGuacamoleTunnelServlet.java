package net.sourceforge.guacamole.net.ausyncguac;

import java.io.*;
import java.util.Arrays;
import java.util.Enumeration;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import net.sourceforge.guacamole.GuacamoleException;
import net.sourceforge.guacamole.net.GuacamoleSocket;
import net.sourceforge.guacamole.net.GuacamoleTunnel;
import net.sourceforge.guacamole.net.InetGuacamoleSocket;
import net.sourceforge.guacamole.protocol.ConfiguredGuacamoleSocket;
import net.sourceforge.guacamole.protocol.GuacamoleClientInformation;
import net.sourceforge.guacamole.protocol.GuacamoleConfiguration;
import net.sourceforge.guacamole.servlet.GuacamoleHTTPTunnelServlet;
import net.sourceforge.guacamole.servlet.GuacamoleSession;
import java.util.List;
import java.util.ArrayList;
import java.lang.Thread;
import java.lang.Process;
import java.lang.ProcessBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.sourceforge.guacamole.net.ausyncguac.AusyncCredentials;

/**
 * The remote visualisation session servlet. It is based on Guacamole.
 */
public class AusyncGuacamoleTunnelServlet extends GuacamoleHTTPTunnelServlet {

    Logger logger = LoggerFactory.getLogger(AusyncGuacamoleTunnelServlet.class);
    private Boolean firstConnect = true;

    private class TunnelThread extends Thread {
        private GuacamoleTunnel tunnelRef = null;
        private String disconnCommand = null;
        private String connectCommand = null;

        public TunnelThread(GuacamoleTunnel tunnel, String discCommand, String conCommand) {
            tunnelRef = tunnel;
            disconnCommand = discCommand;
            connectCommand = conCommand;
        }

        private void executeCommand(String command) {
            if(command != null && !command.isEmpty()) {
                try {
                    ProcessBuilder pb = new ProcessBuilder(command);
                    pb.start();
                } catch (IOException e) {
                    logger.error(e.toString());
                }
            }
        }

        public void run() {
            try {
                // Wait for the tunnel to be open for the first time
                while (!tunnelRef.isOpen()) {
                    // Pause for 10 seconds
                    Thread.sleep(10000);
                }

                // Run as long as tunnel is open
                while (tunnelRef.isOpen()) {
                    // Pause for 1 min
                    Thread.sleep(60000);
                    //Make sure there is no timer running
                    executeCommand(connectCommand);
                }

                // If the tunnel was closed, call the onDisconnectCommand
                executeCommand(disconnCommand);
            } catch (InterruptedException e) {
                logger.error(e.toString());
            }
        }
    }


    private void callVNCServer(String[] args, String username) {
        try {
            //Use ProcessBuilder to allow for arguments with spaces (such as file paths)
            List<String> command = new ArrayList<String>();
            command.add("/usr/bin/sudo");
            command.add("-u");
            command.add(username);
            command.add("/usr/bin/vncserver");
            for (String s : args) {
                command.add(s);
            }

            ProcessBuilder procBuilder = new ProcessBuilder(command);
            procBuilder.environment().put("HOME", "/home/"+username);
            Process proc = procBuilder.start();

            //Read the error output
            String line = "";
            String errorOutput = "";
            BufferedReader stdError = new BufferedReader(new InputStreamReader(proc.getErrorStream()));
            while ((line = stdError.readLine()) != null) {
                errorOutput += line + "\n";
            }

            if (!errorOutput.isEmpty()) {
                logger.error("VNCServer command execution error: \n{}", errorOutput);
            }
        } catch (IOException e) {
            logger.error(e.toString());
        }
    }


    @Override
    protected GuacamoleTunnel doConnect(HttpServletRequest request) throws GuacamoleException {

        //--------------------------------
        //   Perform the authentication
        //--------------------------------
        //Get the credentials from the servlet context
        AusyncCredentials credentials = new AusyncCredentials(getServletContext());

        //Validate the credentials
        if (!credentials.isValid(request)) {
            logger.error("The credentials are not valid!");
            return null;   
        }


        //--------------------------------
        //   Restart the VNC server to
        //   set the screen resolution
        //--------------------------------
        String width = "1024";
        String height = "768";
        String tmp_width = request.getParameter("width");
        String tmp_height = request.getParameter("height");
        if (tmp_width != null) width = tmp_width;
        if (tmp_height != null) height = tmp_height;
        if (getServletContext().getInitParameter("autoResolutionVNC").equals("True") && firstConnect) {
            callVNCServer(new String[] {"-kill", ":1"}, credentials.getUsername());
            callVNCServer(new String[] {"-geometry", width+"x"+height, "-depth", "16", "-dpi", "96", ":1"}, credentials.getUsername());    
        }
        

        //--------------------------------
        //   Connect to the VNC server
        //--------------------------------
        GuacamoleConfiguration config = new GuacamoleConfiguration();
        config.setProtocol("vnc");
        config.setParameter("hostname", "localhost");
        config.setParameter("port", "5901");
        config.setParameter("password", credentials.getPassword());

        // Get client information
        GuacamoleClientInformation info = new GuacamoleClientInformation();
        
        // Set screen resolution for the JavaScript
        info.setOptimalScreenWidth(Integer.parseInt(width));
        info.setOptimalScreenHeight(Integer.parseInt(height));
           
        // Add video mimetypes
        String[] video_mimetypes = request.getParameterValues("video");
        if (video_mimetypes != null)
            info.getVideoMimetypes().addAll(Arrays.asList(video_mimetypes));

        // Connect to guacd
        GuacamoleSocket socket = new ConfiguredGuacamoleSocket(
                new InetGuacamoleSocket("localhost", 4822),
                config, info
        );

        // Establish the tunnel using the connected socket
        GuacamoleTunnel tunnel = new GuacamoleTunnel(socket);

        // Attach tunnel to session
        GuacamoleSession session = new GuacamoleSession(request.getSession(true));
        session.attachTunnel(tunnel);


        //---------------------------------
        // Connection has been established
        //---------------------------------
        // If not empty, call the onConnect command line
        String onConnectCommand = getServletContext().getInitParameter("onConnectCommand");
        if(onConnectCommand != null && !onConnectCommand.isEmpty()) {
            try {
                ProcessBuilder pb = new ProcessBuilder(onConnectCommand);
                pb.start();
            } catch (IOException e) {
                logger.error(e.toString());
            }
        }

        // Start the thread that checks if the tunnel is still open
        if (tunnel != null) {
            new TunnelThread(tunnel,
                             getServletContext().getInitParameter("onDisconnectCommand"),
                             getServletContext().getInitParameter("onConnectCommand")
                             ).start();    
        }

        // Logging
        logger.info("HTTP tunnel established");
        firstConnect = false;

        // Return pre-attached tunnel
        return tunnel;
    }
}
