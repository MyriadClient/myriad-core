package dev.myriadclient.impl.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.myriadclient.api.Myriad;
import dev.myriadclient.api.command.Command;
import dev.myriadclient.api.command.arguments.Arguments;
import dev.myriadclient.api.job.Job;
import dev.myriadclient.api.module.Module;
import dev.myriadclient.impl.service.JobText;
import net.minecraft.commands.SharedSuggestionProvider;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** {@code .jobs} and {@code .job}: what's running, and pausing, resuming or cancelling it by id or by its module. */
final class JobCommands {
	private JobCommands() {
	}

	static Command jobs() {
		return new Command("jobs", "Lists the running jobs, with the id .job takes.") {
			@Override
			public void build(LiteralArgumentBuilder<SharedSuggestionProvider> b) {
				b.executes(c -> {
					List<Job<?>> running = Myriad.jobs().running();
					if (running.isEmpty()) info("No jobs running");
					for (Job<?> j : running) {
						info(String.format(Locale.ROOT, "%d  %s · %s  (%s, %s)", j.id(), JobText.owner(j), JobText.line(j), JobText.state(j.state()),
							JobText.elapsed(j.ticks())));
					}
					return SINGLE_SUCCESS;
				});
			}
		};
	}

	static Command job() {
		return new Command("job", "Pauses, resumes or cancels a job by its id (.jobs lists them), every job of a module, or all.") {
			@Override
			public void build(LiteralArgumentBuilder<SharedSuggestionProvider> b) {
				b.then(argument("action", Arguments.choice(() -> List.of("pause", "resume", "cancel")))
					.then(argument("which", Arguments.suggesting(JobCommands::targets)).executes(c -> {
						String action = StringArgumentType.getString(c, "action"), which = StringArgumentType.getString(c, "which");
						List<Job<?>> jobs = find(which);
						if (jobs.isEmpty()) {
							error("No running job is " + which + " (.jobs lists them)");
							return 0;
						}
						for (Job<?> j : jobs) {
							switch (action) {
								case "pause" -> j.pause();
								case "resume" -> j.resume();
								default -> j.cancel();
							}
						}
						String done = switch (action) {
							case "pause" -> "Paused";
							case "resume" -> "Resumed";
							default -> "Cancelled";
						};
						info(done + " " + (jobs.size() == 1 ? JobText.owner(jobs.getFirst()) + " · " + jobs.getFirst().name() : jobs.size() + " jobs"));
						return SINGLE_SUCCESS;
					})));
			}
		};
	}

	/** "all", the running jobs' ids, and the modules that started them. */
	private static Set<String> targets() {
		Set<String> out = new LinkedHashSet<>();
		out.add("all");
		for (Job<?> j : Myriad.jobs().running()) {
			out.add(String.valueOf(j.id()));
			if (j.owner() instanceof Module m) out.add(m.id().path());
		}
		return out;
	}

	/** The jobs {@code which} names: all of them, one by id (a step under a job too), or a module's by its name or id. */
	private static List<Job<?>> find(String which) {
		if (which.equalsIgnoreCase("all")) return Myriad.jobs().running();
		try {
			return Myriad.jobs().byId(Integer.parseInt(which)).<List<Job<?>>>map(List::of).orElse(List.of());
		} catch (NumberFormatException notAnId) {
			List<Job<?>> out = new ArrayList<>();
			for (Job<?> j : Myriad.jobs().running()) {
				if (j.owner() instanceof Module m && (m.id().path().equalsIgnoreCase(which) || m.name().equalsIgnoreCase(which))) out.add(j);
			}
			return out;
		}
	}
}
