import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

public class ATMApp extends Application {
    private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a");
    private final java.text.DecimalFormat moneyFormat =
            new java.text.DecimalFormat("#,##0.00", java.text.DecimalFormatSymbols.getInstance(Locale.US));
    private AccountManager manager;
    private Accounts currentUser;
    private Stage stage;
    private final LoginAttemptLimiter loginAttemptLimiter = new LoginAttemptLimiter();
    private Timeline lockoutTimer;
    private Label lockoutMessage;
    private Button loginButton;
    private TextField loginId;
    private PasswordField loginPassword;
    private Label dashboardBalance;
    private VBox dashboardContent;

    @Override
    public void start(Stage stage) {
        this.stage = stage;
        try {
            manager = new AccountManager();
        } catch (SQLException e) {
            showError("Database unavailable", "The ATM database could not be opened. " + safeMessage(e));
            stage.close();
            return;
        }
        stage.setTitle("SALAMA BANK · ATM");
        stage.setMinWidth(900);
        stage.setMinHeight(650);
        showAuthScreen(false);
        stage.show();
    }

    private void showAuthScreen(boolean createAccount) {
        VBox form = new VBox(16);
        form.setMaxWidth(390);
        form.setAlignment(Pos.CENTER_LEFT);
        HBox brand = brandLogo(false);
        Label title = new Label(createAccount ? "Open your account" : "Welcome back");
        title.getStyleClass().add("auth-title");
        Label intro = new Label(createAccount ? "A simpler way to manage your money." : "Sign in to your secure banking space.");
        intro.getStyleClass().add("muted");
        form.getChildren().addAll(brand, title, intro, new Separator());

        if (createAccount) {
            TextField name = field("Full name");
            PasswordField password = passwordField("Password (8+ characters, letter and number)");
            PasswordField confirmation = passwordField("Confirm password");
            TextField starting = field("Initial balance (e.g. 250.00)");
            Label message = new Label();
            message.getStyleClass().add("message");
            Button create = primaryButton("Create account");
            create.setMaxWidth(Double.MAX_VALUE);
            Button back = textButton("Back to log in");
            back.setOnAction(event -> showAuthScreen(false));
            create.setOnAction(event -> {
                try {
                    if (!password.getText().equals(confirmation.getText()))
                        throw new IllegalArgumentException("The password confirmations do not match.");
                    BigDecimal amount = parseAmount(starting.getText());
                    char[] passwordChars = password.getText().toCharArray();
                    Accounts account;
                    try {
                        account = manager.createAccount(name.getText(), passwordChars, amount);
                    } finally {
                        java.util.Arrays.fill(passwordChars, '\0');
                    }
                    Alert success = new Alert(Alert.AlertType.INFORMATION,
                            "Your account is ready.\n\nAccount ID: " + account.getId()
                                    + "\nPlease save this ID. You will need it to log in.", ButtonType.OK);
                    success.setHeaderText("Account created successfully");
                    success.showAndWait();
                    password.clear();
                    confirmation.clear();
                    showAuthScreen(false);
                } catch (IllegalArgumentException | SQLException e) {
                    message.setText(safeMessage(e));
                }
            });
            form.getChildren().addAll(labelled("FULL NAME", name), labelled("PASSWORD", password),
                    labelled("CONFIRM PASSWORD", confirmation), labelled("INITIAL BALANCE", starting), message, create, back);
        } else {
            loginId = field("Account ID");
            loginPassword = passwordField("Password");
            lockoutMessage = new Label();
            lockoutMessage.getStyleClass().add("message");
            loginButton = primaryButton("Log in");
            loginButton.setMaxWidth(Double.MAX_VALUE);
            loginButton.setOnAction(event -> attemptLogin());
            Button create = textButton("Create an account  →");
            create.setOnAction(event -> showAuthScreen(true));
            form.getChildren().addAll(labelled("ACCOUNT ID", loginId), labelled("PASSWORD", loginPassword),
                    lockoutMessage, loginButton, create);
            updateLockoutDisplay();
        }

        VBox brandPanel = new VBox(20);
        brandPanel.getStyleClass().add("hero-panel");
        brandPanel.setAlignment(Pos.CENTER_LEFT);
        brandPanel.setPadding(new Insets(48));
        Label heroEyebrow = new Label("BANKING, MADE CLEAR");
        heroEyebrow.getStyleClass().add("eyebrow");
        Label heroTitle = new Label("Your money.\nYour move.");
        heroTitle.getStyleClass().add("hero-title");
        Label heroCopy = new Label("A calm, secure place to manage your everyday banking.");
        heroCopy.getStyleClass().add("muted");
        heroCopy.setWrapText(true);
        brandPanel.getChildren().addAll(heroEyebrow, heroTitle, heroCopy);
        HBox body = new HBox(50, brandPanel, form);
        body.setAlignment(Pos.CENTER);
        body.setPadding(new Insets(40));
        HBox.setHgrow(brandPanel, Priority.ALWAYS);
        Scene scene = new Scene(body, 1120, 740);
        applyStyles(scene);
        stage.setScene(scene);
    }

    private void attemptLogin() {
        try {
            long id = Long.parseLong(loginId.getText().strip());
            char[] passwordChars = loginPassword.getText().toCharArray();
            Accounts account;
            try {
                account = manager.login(id, passwordChars);
            } finally {
                java.util.Arrays.fill(passwordChars, '\0');
            }
            if (account == null) {
                if (loginAttemptLimiter.recordFailure()) {
                    startLockoutTimer();
                } else {
                    lockoutMessage.setText("Incorrect account ID or password. "
                            + loginAttemptLimiter.attemptsRemaining() + " attempts remaining.");
                }
                loginPassword.clear();
                return;
            }
            loginAttemptLimiter.reset();
            currentUser = account;
            showDashboard();
        } catch (NumberFormatException e) {
            lockoutMessage.setText("Enter a valid account ID.");
        } catch (SQLException e) {
            lockoutMessage.setText("Unable to sign in right now. " + safeMessage(e));
        }
    }

    private void startLockoutTimer() {
        if (lockoutTimer != null) lockoutTimer.stop();
        lockoutTimer = new Timeline(new KeyFrame(Duration.seconds(1), event -> updateLockoutDisplay()));
        lockoutTimer.setCycleCount(Timeline.INDEFINITE);
        lockoutTimer.play();
        updateLockoutDisplay();
    }

    private void updateLockoutDisplay() {
        if (loginButton == null || lockoutMessage == null) return;
        long seconds = loginAttemptLimiter.secondsRemaining();
        if (seconds > 0) {
            loginButton.setDisable(true);
            lockoutMessage.setText("Too many attempts. Try again in " + (seconds / 60) + ":" + String.format("%02d", seconds % 60) + ".");
        } else {
            if (loginAttemptLimiter.isLockoutComplete()) {
                if (lockoutTimer != null) lockoutTimer.stop();
                lockoutMessage.setText("You can try signing in again.");
            }
            loginButton.setDisable(false);
        }
    }

    private void showDashboard() {
        dashboardBalance = new Label();
        dashboardBalance.getStyleClass().add("balance-value");
        dashboardContent = new VBox(20);
        dashboardContent.setPadding(new Insets(34));
        dashboardContent.setFillWidth(true);
        showOverview();

        VBox sidebar = new VBox(12);
        sidebar.getStyleClass().add("sidebar");
        sidebar.setPadding(new Insets(28, 18, 24, 18));
        HBox brand = brandLogo(true);
        Label profile = new Label(currentUser.getName() + "\nAccount · " + currentUser.getId());
        profile.getStyleClass().add("profile-label");
        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        sidebar.getChildren().addAll(brand, new Separator(), profile, new Separator());
        sidebar.getChildren().add(navButton("Overview", this::showOverview));
        sidebar.getChildren().add(navButton("Deposit", () -> showMoneyForm("Deposit", false)));
        sidebar.getChildren().add(navButton("Withdraw", () -> showMoneyForm("Withdraw", false)));
        sidebar.getChildren().add(navButton("Transfer", () -> showMoneyForm("Transfer", true)));
        sidebar.getChildren().add(navButton("Transaction history", this::showHistory));
        sidebar.getChildren().addAll(spacer, navButton("Log out", this::logout));
        BorderPane root = new BorderPane();
        root.setLeft(sidebar);
        root.setCenter(new ScrollPane(dashboardContent));
        ((ScrollPane) root.getCenter()).setFitToWidth(true);
        Scene scene = new Scene(root, 1120, 740);
        applyStyles(scene);
        stage.setScene(scene);
    }

    private void showOverview() {
        dashboardContent.getChildren().clear();
        Label heading = heading("Good " + (LocalDateTime.now().getHour() < 12 ? "morning" : "afternoon")
                + ", " + currentUser.getName().split("\\s+")[0]);
        Label subheading = muted("Here's your account at a glance.");
        VBox balanceCard = new VBox(14);
        balanceCard.getStyleClass().add("balance-card");
        Label caption = new Label("AVAILABLE BALANCE");
        caption.getStyleClass().add("eyebrow");
        dashboardBalance.setText(formatMoney(currentUser.getBalance()));
        Label account = new Label("Account  " + currentUser.getId() + "     ·     Since " + DISPLAY_DATE.format(currentUser.getDateCreated()));
        account.getStyleClass().add("balance-caption");
        balanceCard.getChildren().addAll(caption, dashboardBalance, account);
        HBox actions = new HBox(12,
                actionButton("Check balance", () -> showMessage("Available balance", formatMoney(currentUser.getBalance()))),
                actionButton("Deposit", () -> showMoneyForm("Deposit", false)),
                actionButton("Withdraw", () -> showMoneyForm("Withdraw", false)),
                actionButton("Transfer", () -> showMoneyForm("Transfer", true)));
        Label recentHeading = heading("Recent activity");
        dashboardContent.getChildren().addAll(heading, subheading, balanceCard, actions, recentHeading);
        try {
            addTransactionRows(manager.recentTransactions(currentUser.getId(), 5), dashboardContent);
        } catch (SQLException e) {
            dashboardContent.getChildren().add(errorLabel("Could not load recent activity. " + safeMessage(e)));
        }
    }

    private void showMoneyForm(String action, boolean transfer) {
        dashboardContent.getChildren().clear();
        dashboardContent.getChildren().addAll(heading(action), muted(transfer
                ? "Send money instantly to another SALAMA BANK account." : action + " funds securely."));
        VBox card = new VBox(18);
        card.getStyleClass().add("form-card");
        card.setMaxWidth(620);
        TextField recipientField = transfer ? field("Account ID") : null;
        if (recipientField != null) card.getChildren().add(labelled("RECIPIENT ACCOUNT ID", recipientField));
        TextField amount = field("0.00");
        card.getChildren().add(labelled("AMOUNT", amount));
        Label hint = muted(transfer ? "Minimum transfer: 50.00" : "Minimum " + action.toLowerCase() + ": 50.00");
        Label feedback = new Label();
        feedback.getStyleClass().add("message");
        Button submit = primaryButton(action);
        submit.setOnAction(event -> {
            try {
                BigDecimal value = parseAmount(amount.getText());
                if (transfer) {
                    long recipient = Long.parseLong(recipientField.getText().strip());
                    manager.transfer(currentUser, recipient, value);
                } else if (action.equals("Deposit")) {
                    manager.deposit(currentUser, value);
                } else {
                    manager.withdraw(currentUser, value);
                }
                refreshBalance();
                feedback.getStyleClass().remove("error-text");
                feedback.getStyleClass().add("success-text");
                feedback.setText(action + " completed successfully.");
                amount.clear();
                showOverview();
                showMessage(action + " complete", "Your " + action.toLowerCase() + " was completed successfully.");
            } catch (NumberFormatException e) {
                feedback.getStyleClass().add("error-text");
                feedback.setText(transfer ? "Enter a valid recipient account ID." : "Enter a valid amount, for example 125.00.");
            } catch (IllegalArgumentException | SQLException e) {
                feedback.getStyleClass().add("error-text");
                feedback.setText(safeMessage(e));
            }
        });
        card.getChildren().addAll(hint, feedback, submit);
        dashboardContent.getChildren().add(card);
    }

    private void showHistory() {
        dashboardContent.getChildren().clear();
        dashboardContent.getChildren().addAll(heading("Transaction history"), muted("Your latest account activity."));
        try {
            addTransactionRows(manager.recentTransactions(currentUser.getId(), 12), dashboardContent);
        } catch (SQLException e) {
            dashboardContent.getChildren().add(errorLabel("Could not load transaction history. " + safeMessage(e)));
        }
    }

    private void addTransactionRows(List<TransactionRecord> records, VBox parent) {
        if (records.isEmpty()) {
            parent.getChildren().add(muted("No transactions yet. Your activity will appear here."));
            return;
        }
        for (TransactionRecord record : records) {
            String title = switch (record.type()) {
                case "DEPOSIT" -> "Deposit";
                case "WITHDRAWAL" -> "Cash withdrawal";
                case "TRANSFER_IN" -> "Transfer received";
                default -> "Transfer sent";
            };
            String detail = DISPLAY_DATE.format(record.timestamp());
            if (record.relatedAccountId() != null) {
                detail += "  ·  " + (record.type().equals("TRANSFER_IN") ? "From " : "To ")
                        + record.relatedAccountName() + " (#" + record.relatedAccountId() + ")";
            }
            Label amount = new Label((record.type().equals("WITHDRAWAL") || record.type().equals("TRANSFER_OUT") ? "− " : "+ ")
                    + formatMoney(record.amount()));
            amount.getStyleClass().add(record.type().equals("WITHDRAWAL") || record.type().equals("TRANSFER_OUT")
                    ? "transaction-negative" : "transaction-positive");
            VBox info = new VBox(5, new Label(title), muted(detail));
            HBox row = new HBox(info, amount);
            row.getStyleClass().add("transaction-row");
            row.setAlignment(Pos.CENTER_LEFT);
            HBox.setHgrow(info, Priority.ALWAYS);
            parent.getChildren().add(row);
        }
    }

    private void refreshBalance() {
        try {
            Accounts refreshed = manager.findAccount(currentUser.getId());
            if (refreshed != null) currentUser = refreshed;
            dashboardBalance.setText(formatMoney(currentUser.getBalance()));
        } catch (SQLException e) {
            showError("Balance refresh failed", "The transaction was saved, but the latest balance could not be loaded. " + safeMessage(e));
        }
    }

    private void logout() {
        if (lockoutTimer != null) lockoutTimer.stop();
        currentUser = null;
        loginButton = null;
        loginId = null;
        loginPassword = null;
        lockoutMessage = null;
        loginAttemptLimiter.reset();
        showAuthScreen(false);
    }

    private static BigDecimal parseAmount(String text) {
        if (text == null || !text.strip().matches("[0-9]+(?:\\.[0-9]{1,2})?")) {
            throw new IllegalArgumentException("Enter a valid amount, for example 125.00.");
        }
        try {
            BigDecimal amount = new BigDecimal(text.strip());
            amount.setScale(2, java.math.RoundingMode.UNNECESSARY);
            amount.movePointRight(2).longValueExact();
            return amount;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Enter a valid amount, for example 125.00.");
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("Amounts must have no more than two decimal places.");
        }
    }

    private String formatMoney(BigDecimal amount) {
        return "$" + moneyFormat.format(amount);
    }

    private static String safeMessage(Exception e) {
        if (e instanceof SQLException) return "The database operation could not be completed. Please try again.";
        String message = e.getMessage();
        return message == null || message.isBlank() ? "Please try again." : message;
    }

    private VBox labelled(String label, Control control) {
        Label title = new Label(label);
        title.getStyleClass().add("field-label");
        return new VBox(8, title, control);
    }

    private static TextField field(String prompt) {
        TextField field = new TextField();
        field.setPromptText(prompt);
        return field;
    }

    private static PasswordField passwordField(String prompt) {
        PasswordField field = new PasswordField();
        field.setPromptText(prompt);
        return field;
    }

    private static Button primaryButton(String text) {
        Button button = new Button(text);
        button.getStyleClass().add("primary-button");
        return button;
    }

    private static HBox brandLogo(boolean compact) {
        Label symbol = new Label("S");
        symbol.getStyleClass().add("brand-symbol");

        Label name = new Label("SALAMA");
        name.getStyleClass().add("brand-name");
        Label descriptor = new Label("BANK");
        descriptor.getStyleClass().add("brand-descriptor");

        VBox wordmark = new VBox(compact ? 1 : 2, name, descriptor);
        wordmark.getStyleClass().add("brand-wordmark");
        HBox logo = new HBox(compact ? 10 : 13, symbol, wordmark);
        logo.getStyleClass().add("brand-logo");
        logo.setAlignment(Pos.CENTER_LEFT);
        if (compact) logo.getStyleClass().add("brand-logo-compact");
        return logo;
    }

    private static Button textButton(String text) {
        Button button = new Button(text);
        button.getStyleClass().add("text-button");
        return button;
    }

    private static Button actionButton(String text, Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().add("action-button");
        button.setOnAction(event -> action.run());
        return button;
    }

    private static Button navButton(String text, Runnable action) {
        Button button = new Button(text);
        button.getStyleClass().add("nav-button");
        button.setMaxWidth(Double.MAX_VALUE);
        button.setAlignment(Pos.CENTER_LEFT);
        button.setOnAction(event -> action.run());
        return button;
    }

    private static Label heading(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("section-title");
        return label;
    }

    private static Label muted(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("muted");
        return label;
    }

    private static Label errorLabel(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("error-text");
        return label;
    }

    private static void applyStyles(Scene scene) {
        scene.getRoot().setStyle("-fx-font-family: 'Segoe UI';");
        var stylesheet = ATMApp.class.getResource("/styles.css");
        if (stylesheet != null) scene.getStylesheets().add(stylesheet.toExternalForm());
        scene.setFill(Color.web("#0a1220"));
    }

    private static void showMessage(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION, message, ButtonType.OK);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.showAndWait();
    }

    private static void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, message, ButtonType.OK);
        alert.setTitle(title);
        alert.setHeaderText(title);
        alert.showAndWait();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
