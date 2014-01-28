package servlet;

import java.io.IOException;
import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class SimpleServlet extends HttpServlet {
  private String body;
  private ServletConfig config;

  public SimpleServlet(String body) {
    this.body = body;
    this.config = null;
  }

  @Override
  public void init(final ServletConfig config) throws ServletException {
    this.config = config;
  }

  @Override
  protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
    response.setContentType("text/html");
    response.setStatus(HttpServletResponse.SC_OK);

    String pathInfo = request.getPathInfo();
    if (pathInfo != null && pathInfo.length() > 1 && pathInfo.charAt(0) == '/' && config != null)
    {
      response.getWriter().print(config.getInitParameter(pathInfo.substring(1)));
    }
    else
    {
      response.getWriter().print(body);
    }
  }
}