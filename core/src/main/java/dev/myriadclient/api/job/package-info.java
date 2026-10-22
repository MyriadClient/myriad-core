/**
 * Jobs: long-running work as {@link dev.myriadclient.api.job.Step}s, run by {@code Myriad.jobs()} for a module, with a
 * state, a reason when they're stuck ({@link dev.myriadclient.api.job.Why}) and progress. {@link
 * dev.myriadclient.api.job.Steps} has the services as steps; {@link dev.myriadclient.api.job.Step.Run} is how to write
 * your own. Render thread only.
 */
package dev.myriadclient.api.job;
