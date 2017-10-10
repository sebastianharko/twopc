package app;

class ExponentialMovingAverage {
    private double alpha;
    private Double currentValue;
    public ExponentialMovingAverage(double alpha) {
        this.alpha = alpha;
    }

    public void add(double value) {
        if (currentValue == null) {
            currentValue = value;
        } else {
            double newValue = currentValue + alpha * (value - currentValue);
            currentValue = newValue;
        }
    }

    public double getCurrentValue() {
        return currentValue;
    }
}

