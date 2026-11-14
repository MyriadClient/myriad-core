package dev.myriadclient.api.ui.draw;

/**
 * Easing curves for module animations (0..1 in, 0..1 out), as closed formulas: cheaper than a {@link Bezier}, for
 * modules that ease many values each frame. Inputs outside 0..1 aren't clamped.
 */
public enum Easing {
	/** Constant speed. */
	LINEAR {
		@Override
		public double ease(double t) {
			return t;
		}
	},
	/** Starts slow, quadratic. */
	IN_QUAD {
		@Override
		public double ease(double t) {
			return t * t;
		}
	},
	/** Ends slow, quadratic. */
	OUT_QUAD {
		@Override
		public double ease(double t) {
			return 1 - (1 - t) * (1 - t);
		}
	},
	/** Slow at both ends, quadratic. */
	IN_OUT_QUAD {
		@Override
		public double ease(double t) {
			return t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2;
		}
	},
	/** Ends slow, cubic. */
	OUT_CUBIC {
		@Override
		public double ease(double t) {
			return 1 - Math.pow(1 - t, 3);
		}
	},
	/** Slow at both ends, cubic. */
	IN_OUT_CUBIC {
		@Override
		public double ease(double t) {
			return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
		}
	},
	/** Ends very slow, quintic. */
	OUT_QUINT {
		@Override
		public double ease(double t) {
			return 1 - Math.pow(1 - t, 5);
		}
	},
	/** Ends slowest, exponential; exactly 1 at 1. */
	OUT_EXPO {
		@Override
		public double ease(double t) {
			return t >= 1 ? 1 : 1 - Math.pow(2, -10 * t);
		}
	},
	/** Goes about 10% past 1 before settling back. */
	OUT_BACK {
		@Override
		public double ease(double t) {
			double c1 = 1.70158, c3 = c1 + 1;
			return 1 + c3 * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2);
		}
	};

	/** Eased progress for linear progress {@code t}. */
	public abstract double ease(double t);
}
