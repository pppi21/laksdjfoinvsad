package org.nodriver4j.services.exceptions;

/**
 * Exception thrown when a CapSolver API operation fails.
 *
 * <p>This includes network errors, authentication failures, task timeouts,
 * and any error codes returned by the CapSolver API.</p>
 *
 * @see org.nodriver4j.services.CapSolverService
 */
public class CapSolverException extends Exception {

  public CapSolverException(String message) {
    super(message);
  }

  public CapSolverException(String message, Throwable cause) {
    super(message, cause);
  }
}