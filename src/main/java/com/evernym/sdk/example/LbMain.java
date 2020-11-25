package com.evernym.sdk.example;

import com.evernym.verity.sdk.exceptions.ProvisionTokenException;
import com.evernym.verity.sdk.exceptions.UndefinedContextException;
import com.evernym.verity.sdk.exceptions.VerityException;
import com.evernym.verity.sdk.exceptions.WalletOpenException;
import com.evernym.verity.sdk.protocols.issuersetup.IssuerSetup;
import com.evernym.verity.sdk.protocols.issuersetup.v0_6.IssuerSetupV0_6;
import com.evernym.verity.sdk.protocols.provision.Provision;
import com.evernym.verity.sdk.protocols.provision.v0_7.ProvisionV0_7;
import com.evernym.verity.sdk.protocols.updateendpoint.UpdateEndpoint;
import com.evernym.verity.sdk.utils.Context;
import com.evernym.verity.sdk.utils.ContextBuilder;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;

public class LbMain extends Helper {

    Integer port = 4000;

    private String issuerDID;
    private String issuerVerkey;

    @Override
    public int listenerPort() {
        return port;
    }

    public static void main(String[] args) throws IOException, VerityException {

        System.out.println("LB----------------------testing");
        new LbMain().execute();



    }

    @Override
    public void example() throws IOException, VerityException, InterruptedException {
        setup();
    }



    Context provisionAgent() throws IOException, VerityException {
        ProvisionV0_7 provisioner;
        if (consoleYesNo("Provide Provision Token", true)) {
            String token = consoleInput("Token", System.getenv("TOKEN")).trim();
            println("Using provision token: " + ANSII_GREEN + token + ANSII_RESET);
            provisioner = Provision.v0_7(token);
        } else {
            provisioner = Provision.v0_7();
        }

        String verityUrl = consoleInput("Verity Application Endpoint", System.getenv("VERITY_SERVER")).trim();

        if ("".equals(verityUrl)) {
            verityUrl = "http://localhost:9000";
        }

        println("Using Url: " + ANSII_GREEN + verityUrl + ANSII_RESET);

        // create initial Context
        Context ctx = ContextBuilder.fromScratch("examplewallet1", "examplewallet1", verityUrl);

        // ask that an agent by provision (setup) and associated with created key pair
        Context provisioningResponse = null;
        try {
            provisioningResponse = provisioner.provision(ctx);
        }
        catch (ProvisionTokenException e) {
            println(e.toString());
            println("Provisioning failed! Likely causes:");
            println("- token not provided but Verity Endpoint requires it");
            println("- token provided but is invalid or expired");
            System.exit(1);
        }
        return provisioningResponse;
    }

    Context loadContext(File contextFile) throws IOException, WalletOpenException {
        return ContextBuilder.fromJson(
                new JSONObject(
                        new String(Files.readAllBytes(contextFile.toPath()))
                )
        ).build();
    }

    void updateWebhookEndpoint() throws IOException, VerityException {
        String webhookFromCtx = "";

        try {
            webhookFromCtx = context.endpointUrl();
        } catch (UndefinedContextException ignored) {}

        String webhook = consoleInput(String.format("Ngrok endpoint for port(%d)[%s]", port, webhookFromCtx), System.getenv("WEBHOOK_URL")).trim();

        if("".equals(webhook)) {
            webhook = webhookFromCtx;
        }

        println("Using Webhook: " + ANSII_GREEN + webhook + ANSII_RESET);
        context = context.toContextBuilder().endpointUrl(webhook).build();

        // request that verity-application use specified webhook endpoint
        UpdateEndpoint.v0_6().update(context);
    }

    void setupIssuer() throws IOException, VerityException {
        // constructor for the Issuer Setup protocol
        IssuerSetupV0_6 newIssuerSetup = IssuerSetup.v0_6();

        AtomicBoolean setupComplete = new AtomicBoolean(false); // spinlock bool

        // handler for created issuer identifier message
        setupIssuerHandler(newIssuerSetup, setupComplete);
        // request that issuer identifier be created
        newIssuerSetup.create(context);

        // wait for request to complete
        waitFor(setupComplete, "Waiting for setup to complete");
        println("The issuer DID and Verkey must be on the ledger.");

        boolean automatedRegistration = consoleYesNo("Attempt automated registration via https://selfserve.sovrin.org", true);

        if (automatedRegistration) {
            CloseableHttpClient client = HttpClients.createDefault();
            HttpPost httpPost = new HttpPost("https://selfserve.sovrin.org/nym");

            JSONObject payload_builder = new JSONObject();
            payload_builder.accumulate("network", "stagingnet");
            payload_builder.accumulate("did", issuerDID);
            payload_builder.accumulate("verkey", issuerVerkey);
            payload_builder.accumulate("paymentaddr", "");
            String payload = payload_builder.toString();

            StringEntity entity = new StringEntity(payload);
            httpPost.setEntity(entity);
            httpPost.setHeader("Accept", "application/json");
            httpPost.setHeader("Content-type", "application/json");

            HttpResponse response = client.execute(httpPost);
            int statusCode = response.getStatusLine().getStatusCode();

            if (statusCode != 200) {
                println("Something went wrong with contactig Sovrin portal");
                println(String.format("Please add DID (%s) and Verkey (%s) to ledger manually", issuerDID, issuerVerkey));
                waitFor(" Press ENTER when DID is on ledger");
            } else {
                BufferedReader bufReader = new BufferedReader(new InputStreamReader(response.getEntity().getContent()));
                StringBuilder builder = new StringBuilder();
                String line;
                while ((line = bufReader.readLine()) != null) {
                    builder.append(line);
                    builder.append(System.lineSeparator());
                }
                println("Got response from Sovrin portal: " + ANSII_GREEN + builder + ANSII_RESET);
            }
            client.close();
        }
        else {
            println(String.format("Please add DID (%s) and Verkey (%s) to ledger manually", issuerDID, issuerVerkey));
            waitFor("Press ENTER when DID is on ledger");
        }
    }

    private void setupIssuerHandler(IssuerSetupV0_6 newIssuerSetup, AtomicBoolean setupComplete) {
        handle(newIssuerSetup, (String msgName, JSONObject message) -> {
            if("public-identifier-created".equals(msgName))
            {
                printlnMessage(msgName, message);
                issuerDID = message.getJSONObject("identifier").getString("did");
                issuerVerkey = message.getJSONObject("identifier").getString("verKey");
                setupComplete.set(true);
            }
            else {
                nonHandled("Message Name is not handled - "+msgName);
            }
        });
    }

    void issuerIdentifier() throws IOException, VerityException {
        // constructor for the Issuer Setup protocol
        IssuerSetupV0_6 issuerSetup = IssuerSetup.v0_6();

        AtomicBoolean issuerComplete = new AtomicBoolean(false); // spinlock bool

        issuerIdentifierHandler(issuerSetup, issuerComplete);

        // query the current identifier
        issuerSetup.currentPublicIdentifier(context);

        // wait for response from verity-application
        waitFor(issuerComplete, "Waiting for current issuer DID");
    }

    void issuerIdentifierHandler(IssuerSetupV0_6 issuerSetup, AtomicBoolean issuerComplete) {
        // handler for current issuer identifier message
        handle(issuerSetup, (String msgName, JSONObject message) -> {
            if("public-identifier".equals(msgName))
            {
                printlnMessage(msgName, message);
                issuerDID = message.getString("did");
                issuerVerkey = message.getString("verKey");
            }
            issuerComplete.set(true);
        });
    }


    void setup() throws IOException, VerityException {
        File contextFile = new File("verity-context.json");
        if (contextFile.exists()) {
            if (consoleYesNo("Reuse Verity Context (in verity-context.json)", true)) {
                context = loadContext(contextFile);
            } else {
                context = provisionAgent();
            }
        }
        else {
            context = provisionAgent();
        }


        Files.write(contextFile.toPath(), context.toJson().toString(2).getBytes());

        updateWebhookEndpoint();

        printlnObject(context.toJson(), ">>>", "Context Used:");

        Files.write(contextFile.toPath(), context.toJson().toString(2).getBytes());

        //updateConfigs();

        issuerIdentifier();

        if (issuerDID == null) {
            setupIssuer();
        }
    }

}
