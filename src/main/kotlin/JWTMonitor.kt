import burp.api.montoya.BurpExtension
import burp.api.montoya.MontoyaApi
import burp.api.montoya.burpsuite.TaskExecutionEngine
import burp.api.montoya.burpsuite.TaskExecutionEngine.TaskExecutionEngineState
import burp.api.montoya.extension.ExtensionUnloadingHandler
import burp.api.montoya.http.handler.*
import com.auth0.jwt.JWT
import com.auth0.jwt.exceptions.JWTDecodeException
import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import java.text.SimpleDateFormat
import java.time.Duration
import java.time.Instant
import java.util.*
import java.util.Timer
import javax.swing.*

class JWTMonitor : BurpExtension, HttpHandler, ExtensionUnloadingHandler {
    private lateinit var api: MontoyaApi
    private lateinit var taskExecutionEngine: TaskExecutionEngine

    private lateinit var logTextArea: JTextArea
    private lateinit var expirationLabel: JLabel
    private lateinit var jwtInputField: JTextArea
    private lateinit var headerTextArea: JTextArea
    private lateinit var payloadTextArea: JTextArea
    private lateinit var signatureTextArea: JTextArea

    private var expirationTimer: Timer? = null
    private val expirationInit = "Configure a JWT token to start... And enable autopilot to control tasks based on remaining life time"

    private var controlTasksState = false

    override fun initialize(api: MontoyaApi?) {
        this.api = api ?: throw IllegalArgumentException("api cannot be null")

        api.logging().logToOutput("Started loading the extension...")
        api.extension().setName("JWT Monitor")

        api.http().registerHttpHandler(this)
        api.extension().registerUnloadingHandler(this)

        taskExecutionEngine = api.burpSuite().taskExecutionEngine()

        val mainPanel = createUI()
        api.userInterface().registerSuiteTab("JWT Monitor", mainPanel)

        api.logging().logToOutput("JWT Monitor loaded successfully!")
        appendLogEntry("JWT Monitor loaded successfully! Let the hunt begin!")
    }

    override fun extensionUnloaded() {
        // Clean up resources
        expirationTimer?.cancel()
        expirationTimer = null
        api.logging().logToOutput("JWT Monitor unloaded. Timer stopped.")
    }

    private fun createUI(): JPanel {
        val mainPanel = JPanel(BorderLayout())
        mainPanel.border = BorderFactory.createEmptyBorder(10, 10, 10, 10)

        val jwtMonitorTab = JPanel(BorderLayout())
        jwtMonitorTab.border = BorderFactory.createEmptyBorder(10, 10, 10, 10)

        mainPanel.add(jwtMonitorTab)

        val jwtInputPanel = JPanel()
        jwtInputPanel.layout = BorderLayout()
        jwtInputPanel.border = BorderFactory.createEmptyBorder(10, 0, 10, 0)

        jwtInputField = JTextArea(5, 60)
        jwtInputField.isEditable = true
        jwtInputField.lineWrap = true
        jwtInputField.wrapStyleWord = true
        jwtInputField.border = BorderFactory.createTitledBorder("JWT Token:")

        val buttonPanel = JPanel()
        buttonPanel.layout = BoxLayout(buttonPanel, BoxLayout.Y_AXIS)
        buttonPanel.border = BorderFactory.createEmptyBorder(0, 10, 0, 10)
        buttonPanel.alignmentY = JPanel.CENTER_ALIGNMENT

        val saveButton = JButton("Apply")
        val clearButton = JButton("Clear")
        val controlTasksButton = JToggleButton("Autopilot")

        buttonPanel.add(Box.createVerticalGlue())
        buttonPanel.add(saveButton)
        buttonPanel.add(Box.createVerticalStrut(5))
        buttonPanel.add(clearButton)
        buttonPanel.add(Box.createVerticalStrut(5))
        buttonPanel.add(controlTasksButton)
        buttonPanel.add(Box.createVerticalGlue())

        jwtInputPanel.add(jwtInputField, BorderLayout.CENTER)
        jwtInputPanel.add(buttonPanel, BorderLayout.EAST)

        saveButton.addActionListener {
            val jwtToken = jwtInputField.text
            processJWT(jwtToken)
        }

        clearButton.addActionListener {
            jwtInputField.text = ""
            expirationLabel.text = expirationInit
            expirationLabel.foreground = Color.BLACK
            headerTextArea.text = ""
            payloadTextArea.text = ""
            signatureTextArea.text = ""
            expirationTimer?.cancel()
        }

        controlTasksButton.addActionListener {
            controlTasksState = controlTasksButton.isSelected
        }

        expirationLabel = JLabel(expirationInit)
        expirationLabel.font = expirationLabel.font.deriveFont(24f).deriveFont(Font.BOLD)
        expirationLabel.horizontalAlignment = SwingConstants.CENTER
        expirationLabel.border = BorderFactory.createEmptyBorder(10, 0, 10, 0)

        logTextArea = JTextArea(2, 40)
        logTextArea.isEditable = false
        logTextArea.lineWrap = true
        logTextArea.wrapStyleWord = true
        val logScrollPane = JScrollPane(logTextArea)
        logScrollPane.border = BorderFactory.createTitledBorder("LOG:")

        headerTextArea = JTextArea(5, 30)
        headerTextArea.isEditable = false
        headerTextArea.lineWrap = true
        headerTextArea.wrapStyleWord = true
        val headerScrollPane = JScrollPane(headerTextArea)
        headerScrollPane.border = BorderFactory.createTitledBorder("HEADER:")

        payloadTextArea = JTextArea(5, 30)
        payloadTextArea.isEditable = false
        payloadTextArea.lineWrap = true
        payloadTextArea.wrapStyleWord = true
        val payloadScrollPane = JScrollPane(payloadTextArea)
        payloadScrollPane.border = BorderFactory.createTitledBorder("PAYLOAD:")

        signatureTextArea = JTextArea(5, 30)
        signatureTextArea.isEditable = false
        signatureTextArea.lineWrap = true
        signatureTextArea.wrapStyleWord = true
        val signatureScrollPane = JScrollPane(signatureTextArea)
        signatureScrollPane.border = BorderFactory.createTitledBorder("SIGNATURE:")

        val jwtSectionsPanel = JPanel()
        jwtSectionsPanel.layout = BoxLayout(jwtSectionsPanel, BoxLayout.Y_AXIS)
        jwtSectionsPanel.add(headerScrollPane)
        jwtSectionsPanel.add(payloadScrollPane)
        jwtSectionsPanel.add(signatureScrollPane)

        val textAreaPanel = JPanel(BorderLayout())
        textAreaPanel.layout = BoxLayout(textAreaPanel, BoxLayout.X_AXIS)
        textAreaPanel.add(logScrollPane)
        textAreaPanel.add(Box.createHorizontalStrut(10))
        textAreaPanel.add(jwtSectionsPanel)

        val labelAndTextAreaPanel = JPanel(BorderLayout())
        labelAndTextAreaPanel.border = BorderFactory.createEmptyBorder(10, 0, 10, 0)

        labelAndTextAreaPanel.add(jwtInputPanel, BorderLayout.NORTH)
        labelAndTextAreaPanel.add(textAreaPanel, BorderLayout.CENTER)

        jwtMonitorTab.add(expirationLabel, BorderLayout.NORTH)
        jwtMonitorTab.add(labelAndTextAreaPanel, BorderLayout.CENTER)

        val disclaimerLabel = JLabel("Provided by Patrick Schmid (Redguard AG)")
        disclaimerLabel.horizontalAlignment = SwingConstants.CENTER
        disclaimerLabel.border = BorderFactory.createEmptyBorder(10, 0, 0, 0)
        jwtMonitorTab.add(disclaimerLabel, BorderLayout.SOUTH)

        return mainPanel
    }

    private fun appendLogEntry(entry: String) {
        logTextArea.append("${entry}\n")
        logTextArea.caretPosition = logTextArea.document.length
    }

    private fun addJWT(header: String, payload: String, signature: String) {
        val prettyJSON = GsonBuilder().setPrettyPrinting().create()
        headerTextArea.text = prettyJSON.toJson(JsonParser.parseString(Base64.getDecoder().decode(header).toString(Charsets.UTF_8)))
        payloadTextArea.text = prettyJSON.toJson(JsonParser.parseString(Base64.getDecoder().decode(payload).toString(Charsets.UTF_8)))
        signatureTextArea.text = signature
    }

    override fun handleHttpRequestToBeSent(requestToBeSent: HttpRequestToBeSent): RequestToBeSentAction {
        val replacementJWTToken = jwtInputField.text

        requestToBeSent.headers()
            .find { it.name() == "Authorization" }
            ?.let { _ ->
                if (replacementJWTToken.isNotBlank()) {
                    appendLogEntry("Replaced the JWT in the header for a ${requestToBeSent.method()} request to ${requestToBeSent.path()}.")
                    return RequestToBeSentAction.continueWith(requestToBeSent.withUpdatedHeader("Authorization", "$replacementJWTToken"))
                }
            }

        requestToBeSent.headers()
            .find { it.name() == "Cookie" }
            ?.let { cookieHeader ->
                if (replacementJWTToken.isNotBlank()) {
                    val cookies = cookieHeader.value().split("; ")
                    val newCookies = mutableListOf<String>()

                    cookies.forEach { cookie ->
                        val (name, value) = cookie.split("=", limit = 2)
                        if (value.startsWith("eyJ")) {
                            newCookies.add("$name=$replacementJWTToken")
                        } else {
                            newCookies.add(cookie)
                        }
                    }

                    val newCookieHeader = newCookies.joinToString("; ")
                    appendLogEntry("Replaced the JWT in the cookie for a ${requestToBeSent.method()} request to ${requestToBeSent.path()}.")
                    return RequestToBeSentAction.continueWith(
                        requestToBeSent.withUpdatedHeader(
                            "Cookie",
                            newCookieHeader
                        )
                    )
                }
            }

        return RequestToBeSentAction.continueWith(requestToBeSent)
    }

    override fun handleHttpResponseReceived(responseReceived: HttpResponseReceived): ResponseReceivedAction {
        return ResponseReceivedAction.continueWith(responseReceived)
    }

    private fun processJWT(jwtToken: String) {
        try {
            val decodedJWT = JWT.decode(jwtToken)

            if (decodedJWT.expiresAt != null) {
                val inputFormat = SimpleDateFormat("EEE MMM dd HH:mm:ss z yyyy", Locale.US)
                val expiresAtDate = inputFormat.parse(decodedJWT.expiresAt.toString())
                val expiresAtInstant = expiresAtDate!!.toInstant()

                expirationTimer?.cancel()
                expirationTimer = Timer()
                expirationTimer!!.schedule(object : TimerTask() {
                    override fun run() {
                        val now = Instant.now()
                        val minutesToExpiration = Duration.between(now, expiresAtInstant).toMinutes()

                        val formattedExpiration = "$minutesToExpiration minute(s)"
                        expirationLabel.text = "Session Expiration in: $formattedExpiration"

                        if (minutesToExpiration <= 0) {
                            expirationLabel.text = "Session Expired!"
                            expirationLabel.foreground = Color.RED
                            expirationTimer?.cancel()
                        } else if (minutesToExpiration <= 1) {
                            expirationLabel.foreground = Color.RED

                            if (controlTasksState && taskExecutionEngine.state != TaskExecutionEngineState.PAUSED) {
                                taskExecutionEngine.state = TaskExecutionEngineState.PAUSED
                                appendLogEntry("Task execution paused due to imminent JWT expiration.")
                            }
                        } else if (minutesToExpiration <= 3) {
                            expirationLabel.foreground = Color.YELLOW
                        } else {
                            expirationLabel.foreground = Color.BLACK

                            if (controlTasksState && taskExecutionEngine.state != TaskExecutionEngineState.RUNNING) {
                                taskExecutionEngine.state = TaskExecutionEngineState.RUNNING
                                appendLogEntry("Task execution resumed due to new JWT.")
                            }
                        }
                    }
                }, 0, 60000)

                val initialMinutesToExpiration = Duration.between(Instant.now(), expiresAtInstant).toMinutes()
                appendLogEntry("Got a JWT token from issuer \"${decodedJWT.issuer}\". It will expire in $initialMinutesToExpiration minutes.")
                addJWT(decodedJWT.header, decodedJWT.payload, decodedJWT.signature)

            } else {
                appendLogEntry("Got a JWT token from issuer \"${decodedJWT.issuer}\". It has no expiration date.")
                expirationLabel.text = "No expiration date found."
                expirationLabel.foreground = Color.BLACK
                expirationTimer?.cancel()
            }

        } catch (e: JWTDecodeException) {
            appendLogEntry("Got a JWT but could not decode it: ${e.message}")
            expirationLabel.text = expirationInit
            expirationLabel.foreground = Color.BLACK
            headerTextArea.text = ""
            payloadTextArea.text = ""
            signatureTextArea.text = ""
            expirationTimer?.cancel()
        }
    }
}
