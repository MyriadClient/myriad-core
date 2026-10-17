package dev.myriadclient.api.render;

/** Easing curves for module animations (0..1 in, 0..1 out). */
public enum Easing {
	LINEAR {
		@Override
		public double ease(double t) {
			return t;
		}
	},
	IN_QUAD {
		@Override
		public double ease(double t) {
			return t * t;
		}
	},
	OUT_QUAD {
		@Override
		public double ease(double t) {
			return 1 - (1 - t) * (1 - t);
		}
	},
	IN_OUT_QUAD {
		@Override
		public double ease(double t) {
			return t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2;
		}
	},
	OUT_CUBIC {
		@Override
		public double ease(double t) {
			return 1 - Math.pow(1 - t, 3);
		}
	},
	IN_OUT_CUBIC {
		@Override
		public double ease(double t) {
			return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
		}
	},
	OUT_QUINT {
		@Override
		public double ease(double t) {
			return 1 - Math.pow(1 - t, 5);
		}
	},
	OUT_EXPO {
		@Override
		public double ease(double t) {
			return t >= 1 ? 1 : 1 - Math.pow(2, -10 * t);
		}
	},
	OUT_BACK {
		@Override
		public double ease(double t) {
			double c1 = 1.70158, c3 = c1 + 1;
			return 1 + c3 * Math.pow(t - 1, 3) + c1 * Math.pow(t - 1, 2);
		}
	};

	public abstract double ease(double t);
}
