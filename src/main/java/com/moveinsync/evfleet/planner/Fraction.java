package com.moveinsync.evfleet.planner;

import java.math.BigInteger;

/**
 * Exact rational planner arithmetic; validator deliberately uses another numerical implementation.
 *
 * <p>
 * Values are held in primitive {@code long} fields and reduced with a binary GCD. Only when a product or sum would overflow does a value switch to
 * {@link BigInteger}, so results stay exact while ordinary charging calculations avoid big-number allocation.
 */
public final class Fraction implements Comparable<Fraction> {

    public static final Fraction ZERO = of(0);

    private final long numerator;

    private final long denominator;

    // Non-null only for values that do not fit the primitive representation.
    private final BigInteger bigNumerator;

    private final BigInteger bigDenominator;

    /** A value held in primitive longs. */
    private Fraction(long numerator, long denominator) {
        this.numerator = numerator;
        this.denominator = denominator;
        this.bigNumerator = null;
        this.bigDenominator = null;
    }

    /** A value held in BigIntegers (only after an overflow). */
    private Fraction(BigInteger numerator, BigInteger denominator) {
        this.numerator = 0;
        this.denominator = 0;
        this.bigNumerator = numerator;
        this.bigDenominator = denominator;
    }

    /** A whole number. */
    public static Fraction of(long n) {
        return new Fraction(n, 1);
    }

    /** n divided by d, reduced. */
    public static Fraction of(long n, long d) {
        return reduced(n, d);
    }

    /** True when stored as BigInteger. */
    private boolean isBig() {
        return bigNumerator != null;
    }

    /** Numerator as a BigInteger. */
    private BigInteger bigNumerator() {
        return isBig() ? bigNumerator : BigInteger.valueOf(numerator);
    }

    /** Denominator as a BigInteger. */
    private BigInteger bigDenominator() {
        return isBig() ? bigDenominator : BigInteger.valueOf(denominator);
    }

    /** Normalises the sign and divides out the common factor. */
    private static Fraction reduced(long n, long d) {
        if (d == 0) {
            throw new ArithmeticException("zero denominator");
        }
        if (n == Long.MIN_VALUE || d == Long.MIN_VALUE) {
            return reduced(BigInteger.valueOf(n), BigInteger.valueOf(d));
        }
        if (d < 0) {
            n = -n;
            d = -d;
        }

        long gcd = gcd(Math.abs(n), d);

        return new Fraction(n / gcd, d / gcd);
    }

    /** Same for BigIntegers, switching back to longs when the value fits. */
    private static Fraction reduced(BigInteger n, BigInteger d) {
        if (d.signum() == 0) {
            throw new ArithmeticException("zero denominator");
        }
        if (d.signum() < 0) {
            n = n.negate();
            d = d.negate();
        }

        var gcd = n.gcd(d);
        n = n.divide(gcd);
        d = d.divide(gcd);
        if (n.bitLength() < Long.SIZE - 1 && d.bitLength() < Long.SIZE - 1) {
            return new Fraction(n.longValue(), d.longValue());
        }

        return new Fraction(n, d);
    }

    /** Binary GCD of non-negative values; gcd(0, d) is d. */
    private static long gcd(long a, long b) {
        if (a == 0) {
            return b;
        }
        if (b == 0) {
            return a;
        }

        int shift = Long.numberOfTrailingZeros(a | b);
        a >>= Long.numberOfTrailingZeros(a);
        while (b != 0) {
            b >>= Long.numberOfTrailingZeros(b);
            if (a > b) {
                long swap = a;
                a = b;
                b = swap;
            }
            b -= a;
        }

        return a << shift;
    }

    /** This plus x. */
    public Fraction add(Fraction x) {
        if (!isBig() && !x.isBig()) {
            try {
                if (denominator == x.denominator) {
                    return reduced(Math.addExact(numerator, x.numerator), denominator);
                }

                return reduced(Math.addExact(Math.multiplyExact(numerator, x.denominator), Math.multiplyExact(x.numerator, denominator)),
                    Math.multiplyExact(denominator, x.denominator));
            } catch (ArithmeticException overflow) {
                // Fall through to the exact big-number path.
            }
        }

        return reduced(bigNumerator().multiply(x.bigDenominator()).add(x.bigNumerator().multiply(bigDenominator())),
            bigDenominator().multiply(x.bigDenominator()));
    }

    /** This minus x. */
    public Fraction sub(Fraction x) {
        return add(x.negate());
    }

    /** Minus this. */
    private Fraction negate() {
        if (!isBig() && numerator != Long.MIN_VALUE) {
            return new Fraction(-numerator, denominator);
        }

        return reduced(bigNumerator().negate(), bigDenominator());
    }

    /** This times x. */
    public Fraction mul(Fraction x) {
        if (!isBig() && !x.isBig()) {
            try {
                // Cross-reduce first so intermediate products stay small.
                long g1 = gcd(Math.abs(numerator), x.denominator);
                long g2 = gcd(Math.abs(x.numerator), denominator);

                return reduced(Math.multiplyExact(numerator / g1, x.numerator / g2), Math.multiplyExact(denominator / g2, x.denominator / g1));
            } catch (ArithmeticException overflow) {
                // Fall through to the exact big-number path.
            }
        }

        return reduced(bigNumerator().multiply(x.bigNumerator()), bigDenominator().multiply(x.bigDenominator()));
    }

    /** This divided by x. */
    public Fraction div(Fraction x) {
        if (x.signum() == 0) {
            throw new ArithmeticException("division by zero");
        }

        return mul(x.reciprocal());
    }

    /** One divided by this. */
    private Fraction reciprocal() {
        if (!isBig()) {
            return reduced(denominator, numerator);
        }

        return reduced(bigDenominator, bigNumerator);
    }

    /** Sign: -1, 0 or 1. */
    private int signum() {
        return isBig() ? bigNumerator.signum() : Long.signum(numerator);
    }

    /** This times a whole number. */
    public Fraction mul(long x) {
        return mul(of(x));
    }

    /** This divided by a whole number. */
    public Fraction div(long x) {
        return div(of(x));
    }

    /** Compares two values exactly. */
    @Override
    public int compareTo(Fraction x) {
        if (!isBig() && !x.isBig()) {
            if (denominator == x.denominator) {
                return Long.compare(numerator, x.numerator);
            }
            try {
                return Long.compare(Math.multiplyExact(numerator, x.denominator), Math.multiplyExact(x.numerator, denominator));
            } catch (ArithmeticException overflow) {
                // Fall through to the exact big-number path.
            }
        }

        return bigNumerator().multiply(x.bigDenominator()).compareTo(x.bigNumerator().multiply(bigDenominator()));
    }

    /** Equal when the values are equal. */
    @Override
    public boolean equals(Object other) {
        return other instanceof Fraction fraction && compareTo(fraction) == 0;
    }

    /** Hash consistent with equals. */
    @Override
    public int hashCode() {
        return isBig() ? bigNumerator.hashCode() * 31 + bigDenominator.hashCode() : Long.hashCode(numerator) * 31 + Long.hashCode(denominator);
    }

    /** The smaller of the two. */
    public Fraction min(Fraction x) {
        return compareTo(x) < 0 ? this : x;
    }

    /** The larger of the two. */
    public Fraction max(Fraction x) {
        return compareTo(x) > 0 ? this : x;
    }

    /** Rounded down to a whole number. */
    public long floor() {
        if (!isBig()) {
            return Math.floorDiv(numerator, denominator);
        }

        var parts = bigNumerator.divideAndRemainder(bigDenominator);

        return parts[0].longValueExact() - (bigNumerator.signum() < 0 && parts[1].signum() != 0 ? 1 : 0);
    }

    /** Rounded up to a whole number. */
    public long ceil() {
        if (!isBig()) {
            return -Math.floorDiv(-numerator, denominator);
        }

        var parts = bigNumerator.divideAndRemainder(bigDenominator);

        return parts[0].longValueExact() + (bigNumerator.signum() > 0 && parts[1].signum() != 0 ? 1 : 0);
    }
}
