package kr.nospo.identity.application;

public interface EmailSender {
    void send(String recipient, String token);

    class DeliveryFailure extends RuntimeException {
        private final boolean permanent;

        public DeliveryFailure(boolean permanent) {
            super("MAIL_PROVIDER_FAILURE");
            this.permanent = permanent;
        }

        public boolean permanent() {
            return permanent;
        }
    }
}
